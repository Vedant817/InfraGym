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
import java.util.concurrent.locks.LockSupport;

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

    /**
     * How long {@link #flush()} waits for outstanding samples.
     *
     * <p>Generous relative to {@code KafkaTelemetrySender}'s 15 s send timeout, so a
     * single slow batch is waited for rather than abandoned. It exists to stop a flush
     * occupying a worker thread indefinitely, not to be tight.
     */
    private static final long FLUSH_TIMEOUT_SECONDS = 60;

    private final KafkaTopics topics;
    private final SimulationProperties properties;
    private final MeterRegistry meterRegistry;
    private final BatchSender sender;

    private final BlockingQueue<TelemetryEvent> buffer;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong published = new AtomicLong();

    /**
     * Samples accepted into the buffer but not yet published or counted as lost.
     *
     * <p>The thing {@code buffer.isEmpty()} cannot tell you: the drain thread moves a
     * batch out of the queue and holds it while it sends, so an empty buffer does not
     * mean the broker has the data. This counter does, which is what makes a flush a
     * real barrier.
     */
    private final AtomicLong inFlight = new AtomicLong();

    /** Drops per simulation, so a run's own record can say its trace is partial. */
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> droppedBySimulation =
            new java.util.concurrent.ConcurrentHashMap<>();

    private volatile Thread drainThread;
    private volatile boolean drainAlive = true;

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
        drainThread = new Thread(this::drainLoop, "telemetry-drain");
        // Daemon: a stuck drain must never keep a JVM alive on its own.
        drainThread.setDaemon(true);
        drainThread.start();
        log.info("Telemetry publisher ready: topic {}, buffer {}, batch {}",
                topics.simulationTelemetry(), buffer.remainingCapacity(),
                properties.getTelemetryBatchSize());
    }

    /**
     * The drain loop, wrapped so no sender failure can kill it.
     *
     * <p>Previously the body caught only {@code InterruptedException}, so any
     * {@code RuntimeException} from the producer — a closed producer, an oversized
     * record, {@code max.block.ms} exceeded — escaped and terminated the thread. Nothing
     * restarted it and nothing reported it: {@code publish()} went on filling the
     * bounded buffer, every sample from every subsequent run was dropped, and runs still
     * reported {@code COMPLETED} because {@code flush()} runs on the caller's thread.
     * The only trace was a stack trace on stderr.
     */
    private void drainLoop() {
        try {
            drain();
        } catch (Throwable t) {
            meterRegistry.counter("telemetry.drain.died").increment();
            log.error("Telemetry drain thread died; every subsequent sample will be sent "
                    + "inline or dropped. Publishing is degraded until restart.", t);
        } finally {
            // On every exit, not only the exceptional one. drain() also returns normally on
            // close() and on interruption, and leaving drainAlive true then meant publish()
            // went on offering into a buffer nobody was reading -- so the queue filled to
            // 200 000 and every later sample was dropped, while isDrainAlive() reported
            // everything was fine.
            drainAlive = false;

            // Release anything still queued so a flush cannot wait forever on samples
            // nobody is going to take -- and count them, rather than discarding them
            // unremarked. They were offered and they will never arrive, so a run that
            // lost them here is exactly the run that must not be published as
            // telemetryComplete.
            List<TelemetryEvent> stranded = new ArrayList<>();
            buffer.drainTo(stranded);
            if (!stranded.isEmpty()) {
                log.warn("Telemetry drain stopped with {} sample(s) stranded", stranded.size());
                for (TelemetryEvent event : stranded) {
                    countDropped(event);
                }
            }
            inFlight.addAndGet(-stranded.size());
        }
    }

    /** Whether the background drain thread is still running. */
    public boolean isDrainAlive() {
        return drainAlive;
    }

    /** Samples dropped for one simulation, across the process's lifetime. */
    public long droppedFor(String simulationId) {
        AtomicLong counter = droppedBySimulation.get(simulationId);
        return counter == null ? 0 : counter.get();
    }

    /**
     * Forgets one simulation's drop counter.
     *
     * <p>Called once a run has reached its terminal state. The run's drop total is already
     * on its lifecycle event and on the retained {@link SimulationOutcome}, so keeping the
     * entry afterwards only accumulates: the map grows one key and one counter per run for
     * the life of the process, and it grows precisely when the broker is under pressure —
     * that is, exactly when the process is busiest and least able to afford the leak.
     */
    public void forgetDrops(String simulationId) {
        droppedBySimulation.remove(simulationId);
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
            inFlight.incrementAndGet();
            if (drainAlive) {
                return;
            }
            // The drain thread is gone, so nothing else will take this. Send it here
            // rather than letting it sit in a buffer nobody reads until it fills.
            //
            // Only the in-flight accounting moves here. send() settles it when the batch is
            // delivered, and decrementing it at this point as well took it negative and
            // left it there: after one such send, flush() saw `inFlight <= 0`, concluded
            // there was nothing outstanding, and returned immediately for the rest of the
            // process's life — so every later run published COMPLETED while its samples
            // were still queued.
            List<TelemetryEvent> solo = new ArrayList<>(1);
            if (buffer.drainTo(solo, 1) > 0) {
                send(solo);
            }
            return;
        }
        countDropped(event);
    }

    /**
     * Counts a sample that will never reach the broker.
     *
     * <p>Three ways to lose one, all counted here so the number is real:
     * the buffer is full; the drain thread died and nothing will take the sample; and
     * the broker did not acknowledge it. The third used to be counted as a
     * <em>success</em>, so a fifteen-second broker stall produced
     * {@code dropped == 0} and {@code published += batchSize()} — the fiction this
     * method exists to stop.
     */
    private void countDropped(TelemetryEvent event) {
        long total = dropped.incrementAndGet();
        droppedBySimulation
                .computeIfAbsent(event.simulationId(), key -> new AtomicLong())
                .incrementAndGet();
        meterRegistry.counter("telemetry.samples.dropped").increment();
        // Log on powers of two, so a sustained overflow produces one line per doubling
        // rather than one line per sample.
        if (Integer.bitCount((int) Math.min(total, 1 << 30)) == 1) {
            log.warn("Telemetry buffer full; {} samples dropped so far. The broker cannot "
                    + "keep up with the simulation, so these traces are incomplete.", total);
        }
    }

    /**
     * Waits until every offered sample has been published or counted as lost.
     *
     * <p>Called before a run's terminal lifecycle event, and it is the only thing that
     * makes that ordering true. Two defects it replaces, both of which let telemetry
     * arrive <em>after</em> {@code COMPLETED}:
     *
     * <ul>
     *   <li>{@code while (!buffer.isEmpty())} cannot see samples the drain thread has
     *       already moved into its own local batch. Up to one batch size — 500 by
     *       default — escaped the flush entirely, and the aggregator summarised a run it
     *       had not finished receiving.</li>
     *   <li>With two worker threads running concurrently, one run's flush drained the
     *       other's samples, so the "barrier" was over a buffer two producers were
     *       writing to and neither run's ordering was actually established.</li>
     * </ul>
     *
     * <p>Counting in-flight offers is the fix for both: a sample is accounted for only
     * after it has been sent or dropped, and a run's flush cannot return until every
     * sample it offered is accounted for. That last part depends on {@link #send} settling
     * the in-flight count <em>after</em> the counters rather than before it.
     *
     * @return samples lost since this call was made. Informational only — the
     *         authoritative per-run figure is {@link #droppedFor}, which also covers
     *         losses that happened before this call.
     */
    public long flush() {
        long startDropped = dropped.get();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FLUSH_TIMEOUT_SECONDS);

        while (inFlight.get() > 0) {
            if (System.nanoTime() > deadline) {
                long outstanding = inFlight.get();
                log.error("Telemetry flush gave up with {} sample(s) still unaccounted for "
                                + "after {}s. This run's trace is incomplete and its terminal "
                                + "lifecycle event will say so.",
                        outstanding, FLUSH_TIMEOUT_SECONDS);
                meterRegistry.counter("telemetry.flush.timeout").increment();
                return dropped.get() - startDropped;
            }
            // A bounded park, not a spin: the drain thread holds samples while it sends
            // them, and a tight spin here would starve the thread making progress.
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }

        return dropped.get() - startDropped;
    }

    private void drain() {
        int batchSize = Math.max(1, properties.getTelemetryBatchSize());
        List<TelemetryEvent> batch = new ArrayList<>(batchSize);

        // Exits when close() has been called and the queue is empty. Anything still queued
        // afterwards is stranded rather than published; drainLoop's finally accounts for
        // it, which is also the only path that can count those samples as lost.
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
    }

    /**
     * Hands one batch to the broker and settles the accounting for it.
     *
     * <p><b>The broker's answer is honoured.</b> It used to be discarded: the whole batch
     * was counted as published and the in-flight counter was not decremented for samples
     * the broker never acknowledged. Combined with the sender's timeout path counting
     * futures that were merely <em>incomplete</em> as successes, a fifteen-second broker
     * stall produced {@code dropped == 0}, {@code published += batchSize()} and a run
     * reporting {@code COMPLETED} — a complete-looking trace that was missing entirely.
     *
     * <p>Whatever the broker did not accept is counted as dropped and attributed to its
     * simulation, so the run's own record can say its trace is partial.
     *
     * <p><b>The in-flight count is settled last.</b> It used to be decremented in a
     * {@code finally} around the send, which put the decrement <em>before</em> the
     * published and dropped counters were updated. A flush waiting on that counter could
     * therefore observe zero outstanding samples and return while the batch's accounting
     * was still in flight — so the barrier guaranteed the samples had left the queue but
     * not that anything had been counted, and a run could publish
     * {@code telemetryComplete: true} for a batch that was about to be recorded as lost.
     */
    private void send(List<TelemetryEvent> batch) {
        if (batch.isEmpty()) {
            return;
        }
        int offered = batch.size();
        try {
            int accepted;
            try {
                accepted = Math.max(0, Math.min(offered,
                        sender.send(topics.simulationTelemetry(), batch)));
            } catch (RuntimeException e) {
                // A producer-level failure loses the whole batch. Counted, not rethrown:
                // throwing here would kill the drain thread and turn a broker problem into
                // total telemetry loss for the rest of the process's life.
                meterRegistry.counter("telemetry.send.threw").increment();
                log.error("Telemetry batch send failed; {} sample(s) lost", offered, e);
                accepted = 0;
            }

            if (accepted > 0) {
                published.addAndGet(accepted);
                meterRegistry.counter("telemetry.samples.published").increment(accepted);
            }

            // Exactly the tail the broker refused -- one counter per lost sample, not one
            // per sample in the batch. Iterating the whole list whenever anything was
            // lost reported every sample in a partially-acknowledged batch as dropped,
            // so a broker accepting 399 of 400 recorded 400 losses and the totals came
            // to more than were ever offered.
            for (int i = accepted; i < offered; i++) {
                countDropped(batch.get(i));
            }
        } catch (RuntimeException e) {
            // The accounting itself failed. The samples are still lost and the in-flight
            // count still has to fall, or flush() waits out its entire timeout.
            meterRegistry.counter("telemetry.settle.threw").increment();
            log.error("Telemetry batch accounting failed; {} sample(s) unaccounted for", offered, e);
        } finally {
            inFlight.addAndGet(-offered);
        }
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

    /**
     * Samples accepted into the buffer and not yet published or counted as lost.
     *
     * <p>Exposed so a caller can tell whether a flush has anything to wait for. With
     * {@code workerThreads} above 1 the barrier is process-wide, so flushing
     * unconditionally lets a run with no telemetry of its own block on another run's.
     */
    public long inFlightSamples() {
        return inFlight.get();
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
