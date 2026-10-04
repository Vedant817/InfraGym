package com.archforge.simulation.event;

import com.archforge.contracts.event.TelemetryEvent;
import com.archforge.messaging.KafkaTopics;
import com.archforge.simulation.config.SimulationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes telemetry samples to {@code simulation.telemetry}, in batches, from a
 * single drain thread.
 *
 * <h2>Why samples go through a queue</h2>
 * The simulator's observer fires once per component per metric per tick. Publishing
 * synchronously there would put a network round trip inside the tick loop, making the
 * simulation's speed a function of broker latency, and would block the worker thread —
 * the resource just sized to keep runs moving.
 *
 * <h2>Why the queue is bounded, and drops are counted</h2>
 * An unbounded queue turns a broker slowdown into heap exhaustion: the producer keeps
 * accepting, the broker keeps lagging, and the process dies having lost every
 * in-flight run at once. A bounded queue with a counted drop degrades instead — the run
 * finishes, its summary is still published, and {@code telemetry.samples.dropped} says
 * exactly how much of the trace is missing, so nobody reads a partial chart as a
 * complete one.
 *
 * <h2>Ordering</h2>
 * Every sample for one run is keyed by {@code simulationId}, so a run's samples land
 * on one partition and the aggregator windows them in order. Keying by
 * {@code simulationId + nodeId} would spread each run across partitions and destroy
 * the guarantee that a single partition per run exists to provide.
 */
@Slf4j
@Component
public class TelemetryPublisher implements InitializingBean, AutoCloseable {

    /** How long the drain loop waits for a batch before flushing what it has. */
    private static final long POLL_MILLIS = 200;

    /** Upper bound on how long close() waits for the drain thread. */
    private static final long SHUTDOWN_WAIT_MILLIS = 5_000;

    private final KafkaTopics topics;
    private final SimulationProperties properties;
    private final MeterRegistry meterRegistry;
    private final BatchSender sender;

    private final BlockingQueue<TelemetryEvent> buffer;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong published = new AtomicLong();

    private volatile Thread drainThread;

    public TelemetryPublisher(KafkaTopics topics,
                              SimulationProperties properties,
                              MeterRegistry meterRegistry,
                              BatchSender sender) {
        this.topics = topics;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.sender = sender;
        // Bounded by count, not bytes: one sample is a fixed-size record, so a count is
        // a real memory bound and needs no size estimator to be trustworthy.
        this.buffer = new LinkedBlockingQueue<>(Math.max(1000, properties.getTelemetryBufferSize()));
    }

    @Override
    public void afterPropertiesSet() {
        drainThread = new Thread(this::drain, "telemetry-drain");
        // Daemon: a stuck drain must never keep a JVM alive on its own.
        drainThread.setDaemon(true);
        drainThread.start();
        log.info("Telemetry publisher ready: topic {}, buffer {}, batch {}",
                topics.simulationTelemetry(), buffer.remainingCapacity(),
                properties.getTelemetryBatchSize());
    }

    /**
     * Enqueues one sample. Never blocks and never throws.
     *
     * <p>Blocking here would let a broker outage stop the simulation; throwing would
     * kill a run that has already done its work. The sample is dropped and counted
     * instead, and the run still reports.
     */
    public void publish(TelemetryEvent event) {
        if (event == null) {
            return;
        }
        if (buffer.offer(event)) {
            return;
        }
        long total = dropped.incrementAndGet();
        meterRegistry.counter("telemetry.samples.dropped").increment();
        // Log on powers of two, so a sustained overflow produces one line per doubling
        // rather than one line per sample.
        if (Integer.bitCount((int) Math.min(total, 1 << 30)) == 1) {
            log.warn("Telemetry buffer full; {} samples dropped so far. The broker cannot "
                    + "keep up with the simulation, so these traces are incomplete.", total);
        }
    }

    /**
     * Flushes everything currently queued and waits for the broker to acknowledge.
     *
     * <p>Called before a run's terminal lifecycle event is published. Without it the
     * aggregator can observe {@code COMPLETED} and build a summary from a partial
     * trace — the failure mode that made evaluation fire on half a run.
     *
     * <p>Loops because the drain thread may take samples between this thread's
     * snapshot and its {@code drainTo}.
     */
    public void flush() {
        int batchSize = Math.max(1, properties.getTelemetryBatchSize());
        while (!buffer.isEmpty()) {
            List<TelemetryEvent> batch = new ArrayList<>(batchSize);
            buffer.drainTo(batch, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            send(batch);
        }
    }

    private void drain() {
        int batchSize = Math.max(1, properties.getTelemetryBatchSize());
        List<TelemetryEvent> batch = new ArrayList<>(batchSize);

        while (running.get() || !buffer.isEmpty()) {
            try {
                TelemetryEvent first = buffer.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                buffer.drainTo(batch, batchSize - 1);
                send(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                batch.clear();
            }
        }

        // Shutting down: do not silently discard what is left queued.
        List<TelemetryEvent> remainder = new ArrayList<>();
        buffer.drainTo(remainder);
        if (!remainder.isEmpty()) {
            log.warn("Publishing {} telemetry samples left over at shutdown", remainder.size());
            send(remainder);
        }
    }

    private void send(List<TelemetryEvent> batch) {
        if (batch.isEmpty()) {
            return;
        }
        sender.send(topics.simulationTelemetry(), batch);
        published.addAndGet(batch.size());
        meterRegistry.counter("telemetry.samples.published").increment(batch.size());
    }

    /** Samples discarded because the buffer was full, since startup. */
    public long droppedSamples() {
        return dropped.get();
    }

    public long publishedSamples() {
        return published.get();
    }

    /** Samples still queued and not yet handed to the broker. */
    public int bufferedSamples() {
        return buffer.size();
    }

    @Override
    public void close() {
        running.set(false);
        Thread thread = drainThread;
        if (thread == null) {
            return;
        }
        try {
            thread.join(SHUTDOWN_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            log.warn("Telemetry drain thread did not finish within {} ms; {} samples undelivered",
                    SHUTDOWN_WAIT_MILLIS, buffer.size());
        }
    }

    /**
     * Hands a batch of samples to the broker.
     *
     * <p>An interface so the batching, ordering and drop-accounting above can be tested
     * without a broker: a test supplies a recording implementation and asserts on what
     * would have been published. Serialisation and keying belong to the implementation,
     * because only it knows the partition guarantee the batch has to preserve.
     */
    public interface BatchSender {

        /**
         * @param topic  destination topic
         * @param events samples in emission order; may span several simulations
         * @return number accepted; less than the input means some were lost
         */
        int send(String topic, List<TelemetryEvent> events);
    }
}
