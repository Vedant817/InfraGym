package com.archforge.telemetry.aggregate;

import com.archforge.contracts.event.SimulationLifecycleEvent;
import com.archforge.contracts.event.TelemetryEvent;
import com.archforge.telemetry.config.AggregatorProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Consumes {@code simulation.telemetry} and {@code simulation.lifecycle}, and turns the
 * first into windows and the second into a decision about when the second is complete.
 *
 * <p><b>Two listeners, one registry.</b> They exist because the engine's two topics have no
 * ordering relationship with each other, and the pipeline depends on one: the engine
 * flushes its samples and waits for the broker before it publishes {@code COMPLETED}, so
 * every sample is in the log first — but "in the log" is not "consumed here". A roll-up
 * built the moment a terminal event is seen is routinely missing the run's last samples.
 *
 * <p>So the terminal event is parked in {@link RunRegistry} until the telemetry side says
 * it has seen the run's final tick, or a bounded grace period expires. Both outcomes
 * publish; they differ only in whether the roll-up is complete, and the summary says which.
 */
@Slf4j
@Component
public class TelemetryConsumer {

    private final ObjectMapper objectMapper;
    private final AggregatorProperties properties;
    private final RunRegistry registry;
    private final AggregatePublisher publisher;
    private final MeterRegistry meterRegistry;

    public TelemetryConsumer(ObjectMapper objectMapper,
                             AggregatorProperties properties,
                             RunRegistry registry,
                             AggregatePublisher publisher,
                             MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.registry = registry;
        this.publisher = publisher;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${archforge.kafka.topics.simulation-telemetry:simulation.telemetry}",
            groupId = "${archforge.aggregator.consumer-group:telemetry-aggregator-group}",
            containerFactory = "archforgeKafkaListenerContainerFactory",
            concurrency = "${archforge.kafka.listener.concurrency:2}"
    )
    public void onTelemetry(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        TelemetryEvent event;
        try {
            event = objectMapper.readValue(payload, TelemetryEvent.class);
        } catch (Exception e) {
            // Thrown rather than swallowed: the container's error handler classifies it
            // non-retryable and dead-letters it, which is the only way an operator learns
            // the engine published something this service cannot read. Swallowing it
            // produced a silent gap in every chart and a green health check — which is how
            // the previous pipeline lost every record while reporting success.
            meterRegistry.counter("aggregator.telemetry.unreadable").increment();
            throw new IllegalArgumentException(
                    "Unreadable telemetry record: " + e.getMessage(), e);
        }
        if (event == null || event.simulationId() == null || event.simulationId().isBlank()) {
            meterRegistry.counter("aggregator.telemetry.unidentified").increment();
            throw new IllegalArgumentException("Telemetry record has no simulationId");
        }

        if (registry.isRolledUp(event.simulationId())) {
            // The run is finished and summarised. Its tombstone is still in place because a
            // sample can legitimately arrive after the roll-up when this consumer was behind,
            // and re-creating the accumulator would have the run summarised a second time.
            meterRegistry.counter("aggregator.telemetry.after_rollup").increment();
            return;
        }

        RunAggregation run = registry.acquire(event.simulationId(),
                properties.getWindowSizeTicks(), properties.getTickMillis());
        List<RunAggregation.ClosedWindow> closed = run.accept(event);
        publisher.publishWindows(run, closed);
        registry.recordPublished(event.simulationId(), closed);
        meterRegistry.counter("aggregator.telemetry.consumed").increment();

        // The run's last tick means there is nothing more to wait for.
        if (run.lastTickSeen() >= run.totalTicks() && run.totalTicks() > 0) {
            registry.signalLastSampleSeen(event.simulationId());
        }
        registry.evictIfOverCapacity();
    }

    @KafkaListener(
            topics = "${archforge.kafka.topics.simulation-lifecycle:simulation.lifecycle}",
            groupId = "${archforge.aggregator.consumer-group:telemetry-aggregator-group}",
            containerFactory = "archforgeKafkaListenerContainerFactory",
            concurrency = "${archforge.kafka.listener.concurrency:2}"
    )
    public void onLifecycle(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        SimulationLifecycleEvent event;
        try {
            event = objectMapper.readValue(payload, SimulationLifecycleEvent.class);
        } catch (Exception e) {
            meterRegistry.counter("aggregator.lifecycle.unreadable").increment();
            throw new IllegalArgumentException(
                    "Unreadable simulation lifecycle record: " + e.getMessage(), e);
        }
        if (event == null || event.simulationId() == null || event.simulationId().isBlank()) {
            return;
        }
        if (!event.status().isTerminal()) {
            // QUEUED and RUNNING are progress, not completion. Acting on them is how the
            // previous evaluator fired mid-run on partial data.
            meterRegistry.counter("aggregator.lifecycle.progress").increment();
            return;
        }

        if (registry.isRolledUp(event.simulationId())) {
            meterRegistry.counter("aggregator.lifecycle.duplicate").increment();
            return;
        }

        RunAggregation run = registry.acquire(event.simulationId(),
                properties.getWindowSizeTicks(), properties.getTickMillis());
        run.applyTerminal(event);
        meterRegistry.counter("aggregator.lifecycle.terminal", "status",
                event.status().getValue()).increment();

        registry.onTerminal(event.simulationId(), event.totalTicks(), event.isTraceComplete(),
                event.droppedSamples(), () -> finish(run));
    }

    /**
     * Publishes a run's remaining windows and its summary, then releases its memory.
     *
     * <p>Releasing matters: the registry holds a run's open windows, which is the
     * process's dominant memory consumer, and a run that was never evicted would keep its
     * samples for the life of the process.
     */
    private void finish(RunAggregation run) {
        if (!registry.claimRollup(run.simulationId())) {
            log.warn("Simulation {} was already rolled up; discarding a second set of windows "
                    + "({} left over) rather than publishing the run twice",
                    run.simulationId(), run.drain().size());
            registry.discard(run.simulationId());
            return;
        }

        List<RunAggregation.ClosedWindow> remaining = run.drain();
        publisher.publishWindows(run, remaining);

        List<RunAggregation.ClosedWindow> all =
                new ArrayList<>(registry.publishedFor(run.simulationId()));
        all.addAll(remaining);
        publisher.publishSummary(run, all);

        log.info("Aggregated simulation {}: {} components, {} series, {} windows published, "
                        + "offered {} / served {} / failed {} / queued {}, traceComplete={}",
                run.simulationId(), run.nodeCount(), run.seriesCount(), all.size(),
                run.totalOffered(), run.totalServed(), run.totalFailed(), run.totalBacklogged(),
                run.traceComplete());

        registry.discard(run.simulationId());
    }

    /** Releases terminal events whose grace period expired without the run's last samples. */
    @Scheduled(fixedDelayString = "${archforge.aggregator.parked-sweep-millis:500}")
    public void sweepParkedTerminals() {
        registry.releaseExpired(TimeUnit.MILLISECONDS.toNanos(properties.getTerminalGraceMillis()));
    }

    /**
     * Forgets roll-up tombstones for runs that finished long ago.
     *
     * <p>They hold no samples, but one entry per run is still unbounded in a service that
     * runs for months. A generous retention — far longer than the terminal grace period, and
     * far longer than any plausible consumer lag — means no run can still be legitimately
     * arriving while its tombstone is gone.
     */
    @Scheduled(fixedDelayString = "${archforge.aggregator.parked-sweep-millis:500}")
    public void sweepTombstones() {
        int forgotten = registry.sweepFinishedTombstones(60 * 60 * 1000L);
        if (forgotten > 0) {
            meterRegistry.counter("aggregator.tombstones.forgotten").increment(forgotten);
        }
    }
}