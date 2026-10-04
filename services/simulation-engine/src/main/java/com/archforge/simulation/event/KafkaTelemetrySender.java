package com.archforge.simulation.event;

import com.archforge.contracts.event.TelemetryEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sends telemetry batches to Kafka, keyed per simulation.
 *
 * <h2>Keying</h2>
 * Each sample is keyed by its own {@code simulationId}, so all of one run's samples
 * land on a single partition. That is the property the aggregator depends on: it can
 * window a run without a global buffer or a distributed lock, and it can rely on
 * seeing a run's ticks in order. Keying the whole batch once would force unrelated
 * runs onto one partition and halve throughput for no benefit.
 *
 * <h2>Backpressure</h2>
 * Every send is awaited, bounded by {@link #AWAIT_TIMEOUT_SECONDS}. Awaiting is what
 * makes the buffer meaningful: without it {@code KafkaTemplate.send} only queues into
 * the producer's own unbounded accumulator and reports success immediately, so the
 * bounded buffer upstream of it never fills, the drop counter never moves, and a dead
 * broker looks exactly like a healthy one until the heap goes.
 *
 * <p>A send that fails is counted and skipped. The run continues and still publishes
 * its summary, which records how many samples were lost — so a partial trace is
 * visibly partial rather than silently wrong.
 */
@Slf4j
@Component
public class KafkaTelemetrySender implements TelemetryPublisher.BatchSender {

    /**
     * How long a batch may take to be acknowledged.
     *
     * <p>Shorter than the drain loop's poll interval, so one stalled batch cannot stop
     * the queue draining entirely.
     */
    static final int AWAIT_TIMEOUT_SECONDS = 15;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public KafkaTelemetrySender(KafkaTemplate<String, String> kafkaTemplate,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public int send(String topic, List<TelemetryEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>(events.size());

        for (TelemetryEvent event : events) {
            String payload;
            try {
                payload = objectMapper.writeValueAsString(event);
            } catch (Exception e) {
                // Our own contract failing to serialise is a code defect. Count it and
                // skip the sample; a poison payload must not stall the batch.
                meterRegistry.counter("telemetry.serialize_failed").increment();
                log.error("Failed to serialise telemetry for simulation {} node {}",
                        event.simulationId(), event.nodeId(), e);
                continue;
            }
            // Keyed by simulation so one run occupies one partition.
            futures.add(kafkaTemplate.send(topic, event.simulationId(), payload));
        }

        // A sample that failed to serialise never became a future, so it is simply not in
        // `futures` and is excluded by counting from `futures`, not from the batch.
        //
        // This line previously read `futures.size() - awaitAll(...) - serialiseFailures`,
        // which subtracted the serialisation failures a second time. A 500-sample batch with
        // 2 failures and all 498 sends acknowledged reported 496 accepted instead of 498,
        // so the publisher counted 4 losses rather than 2, attributed them to the first four
        // samples in the batch rather than the two that actually failed, and under-counted
        // the published total by two — to the wrong simulation whenever a batch spanned
        // more than one.
        return Math.max(0, futures.size() - awaitAll(topic, futures));
    }

    /**
     * Awaits every send in the batch.
     *
     * <p><b>A timed-out batch is a failure, not a success.</b> The previous version
     * counted only futures already {@code isCompletedExceptionally()}, which is the
     * empty set when the timeout fires — every send is still in flight, waiting on
     * {@code delivery.timeout.ms}. So a stalled broker returned "nothing lost" and the
     * whole batch was accounted for as published.
     *
     * <p>Anything not confirmed within the timeout is now reported as unacknowledged.
     * It may still land — {@code delivery.timeout.ms} is 120 s against this 15 s bound —
     * so this over-reports rather than under-reports, which is the right direction for a
     * number whose purpose is to say a trace may be incomplete.
     *
     * @return number that failed or went unacknowledged
     */
    private int awaitAll(String topic, List<CompletableFuture<SendResult<String, String>>> futures) {
        if (futures.isEmpty()) {
            return 0;
        }
        try {
            CompletableFuture
                    .allOf(futures.toArray(new CompletableFuture[0]))
                    .get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            meterRegistry.counter("telemetry.send.interrupted").increment();
            return futures.size();
        } catch (Exception e) {
            long confirmed = futures.stream()
                    .filter(future -> future.isDone() && !future.isCompletedExceptionally())
                    .count();
            int unacknowledged = futures.size() - (int) confirmed;
            meterRegistry.counter("telemetry.send.failed", "topic", topic).increment(unacknowledged);
            log.warn("Telemetry batch to {}: {} of {} sends were not acknowledged within {} s "
                            + "({}). They may still be in flight, so the run's trace is treated "
                            + "as incomplete.", unacknowledged, futures.size(),
                    AWAIT_TIMEOUT_SECONDS, e.getClass().getSimpleName());
            return unacknowledged;
        }
    }
}
