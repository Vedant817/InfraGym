package com.archforge.simulation.event;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.event.TelemetryEvent;
import com.archforge.contracts.lifecycle.MetricNames;
import com.archforge.messaging.KafkaTopics;
import com.archforge.simulation.config.SimulationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * The publish path: what reaches the broker, what is counted as lost, and — the part
 * that matters — what a flush actually guarantees.
 *
 * <p>No broker. {@link TelemetryPublisher.BatchSender} is the seam the class exists to
 * provide, and every assertion here is about accounting the class performs itself:
 * batching, ordering, drop counting and the flush barrier. Serialisation and keying
 * belong to the sender implementation and are exercised against a real broker by the
 * pipeline harness, not here.
 */
class TelemetryPublisherTest {

    private static final String TELEMETRY_TOPIC = "simulation.telemetry";

    /** Collects everything offered, and can be told to stall or to reject part of a batch. */
    private static final class RecordingSender implements TelemetryPublisher.BatchSender {
        private final List<TelemetryEvent> received = new CopyOnWriteArrayList<>();
        private final AtomicInteger batches = new AtomicInteger();
        private final AtomicInteger acceptedTotal = new AtomicInteger();

        /** Signalled on entry to send(), so a test can wait for the drain thread to arrive. */
        private volatile CountDownLatch entered;
        /** When set, send() parks here before returning. */
        private volatile CountDownLatch release;
        private volatile int acceptFromEachBatch = Integer.MAX_VALUE;
        private volatile RuntimeException explode;

        @Override
        public int send(String topic, List<TelemetryEvent> events) {
            batches.incrementAndGet();
            CountDownLatch gate = entered;
            if (gate != null) {
                gate.countDown();
            }
            CountDownLatch hold = release;
            if (hold != null) {
                try {
                    // Park for real: the point of the barrier test is that flush() cannot
                    // return while a batch is held, so a timed wait would not prove it.
                    hold.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (explode != null) {
                throw explode;
            }
            int accepted = Math.min(events.size(), acceptFromEachBatch);
            received.addAll(events.subList(0, accepted));
            acceptedTotal.addAndGet(accepted);
            return accepted;
        }
    }

    private static KafkaTopics topics() {
        return new KafkaTopics(
                "design.submissions", "design.updates", "submission.lifecycle",
                "simulation.tasks", TELEMETRY_TOPIC, "simulation.lifecycle",
                "aggregated.metrics", "aggregated.summary", "evaluation.results",
                "evaluation.lifecycle", "design.submissions.dlt", "simulation.tasks.dlt",
                "simulation.telemetry.dlt", "aggregated.metrics.dlt",
                "evaluation.results.dlt", "submission.lifecycle.dlt");
    }

    private TelemetryPublisher publisher;

    @AfterEach
    void closePublisher() {
        // Without this every test leaks a drain thread. They are daemon threads so the
        // JVM still exits, but a few hundred leaked across a suite is a few hundred
        // threads parked in poll().
        if (publisher != null) {
            publisher.close();
        }
    }

    private TelemetryPublisher publisher(SimulationProperties properties, RecordingSender sender) {
        publisher = new TelemetryPublisher(topics(), properties, new SimpleMeterRegistry(), sender);
        publisher.afterPropertiesSet();
        return publisher;
    }

    private static TelemetryEvent sample(String simulationId, int index) {
        return TelemetryEvent.of(simulationId, "sub-" + simulationId, "api", NodeType.SERVICE,
                MetricNames.LATENCY_MS, 20 + index, index, "steady", false);
    }

    // ------------------------------------------------------------------ the barrier

    @Nested
    @DisplayName("flush()")
    class Flush {

        @Test
        @Timeout(30)
        @DisplayName("waits for samples the drain thread is already holding")
        void waitsForInFlightSamples() throws InterruptedException {
            // The defect this exists for: the old flush waited on buffer.isEmpty(). The
            // drain thread moves a batch out of the queue and holds it while sending, so
            // an empty buffer did not mean the broker had the data. Up to a full batch --
            // 500 samples by default -- escaped the flush, and the aggregator built its
            // summary from a partial trace, which is how evaluation used to fire on five
            // messages' worth of data.
            RecordingSender sender = new RecordingSender();
            SimulationProperties properties = new SimulationProperties();
            TelemetryPublisher p = publisher(properties, sender);

            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch sendEntered = new CountDownLatch(1);
            sender.release = holding;
            sender.entered = sendEntered;

            // One sample first, so the drain thread has something to poll and parks inside
            // send() where the test controls it. Publishing all 50 up front races: the
            // drain thread can wake on the first offer, take one sample, find the queue
            // still empty and block in send() holding one -- which is the same situation
            // but arrived at by luck.
            p.publish(sample("sim-1", 0));
            assertThat(sendEntered.await(10, TimeUnit.SECONDS))
                    .as("the drain thread picked the first sample up and parked in send()")
                    .isTrue();

            for (int i = 1; i < 50; i++) {
                p.publish(sample("sim-1", i));
            }

            assertThat(p.bufferedSamples())
                    .as("49 samples queued behind a batch the drain thread is holding")
                    .isEqualTo(49);

            CountDownLatch flushed = new CountDownLatch(1);
            new Thread(() -> {
                p.flush();
                flushed.countDown();
            }).start();

            Thread.sleep(300);
            assertThat(flushed.getCount())
                    .as("flush() must not return while a batch is still being sent")
                    .isEqualTo(1);

            holding.countDown();
            assertThat(flushed.await(20, TimeUnit.SECONDS))
                    .as("flush() returns once the sender is released")
                    .isTrue();

            assertThat(sender.received).hasSize(50);
            assertThat(p.droppedSamples()).isZero();
        }

        @Test
        @Timeout(30)
        @DisplayName("reports what was dropped, per run")
        void reportsFlushDrops() throws InterruptedException {
            RecordingSender sender = new RecordingSender();
            SimulationProperties properties = new SimulationProperties();
            // A batch size of 1 makes the accounting exact and independent of how the
            // drain thread happened to coalesce the queue: one sample in, one decision
            // out. With the default 500 the same 5 samples can be one batch of 5 or five
            // batches of 1, and a per-batch accept count then means different totals.
            properties.setTelemetryBatchSize(1);
            sender.acceptFromEachBatch = 0;
            TelemetryPublisher p = publisher(properties, sender);

            for (int i = 0; i < 5; i++) {
                p.publish(sample("sim-1", i));
            }
            p.flush();

            assertSoftly(softly -> {
                softly.assertThat(p.publishedSamples())
                        .as("nothing was accepted, so nothing may be counted as published")
                        .isZero();
                softly.assertThat(p.droppedSamples())
                        .as("every sample the broker refused is counted")
                        .isEqualTo(5);
                softly.assertThat(p.droppedFor("sim-1"))
                        .as("the run's own record has to be able to say its trace is partial")
                        .isEqualTo(5);
                softly.assertThat(p.droppedSamples() + p.publishedSamples())
                        .as("every offered sample is accounted for exactly once")
                        .isEqualTo(5);
            });
        }

        @Test
        @Timeout(30)
        @DisplayName("returns immediately when nothing is outstanding")
        void emptyFlushIsCheap() {
            TelemetryPublisher p = publisher(new SimulationProperties(), new RecordingSender());

            assertThat(p.flush()).isZero();
        }
    }

    // ------------------------------------------------------------------ drop accounting

    @Nested
    @DisplayName("drop accounting")
    class Drops {

        @Test
        @Timeout(30)
        @DisplayName("a full buffer drops and counts, rather than blocking the simulator")
        void fullBufferDrops() throws InterruptedException {
            RecordingSender sender = new RecordingSender();
            sender.release = new CountDownLatch(1);
            SimulationProperties properties = new SimulationProperties();
            properties.setTelemetryBufferSize(1000);
            TelemetryPublisher p = publisher(properties, sender);

            // The drain thread takes one batch and parks. Everything after that fills the
            // 1000-slot buffer and overflows.
            for (int i = 0; i < 5000; i++) {
                p.publish(sample("sim-1", i));
            }

            assertThat(p.droppedSamples())
                    .as("a broker the simulator outruns must degrade, not deadlock the tick loop")
                    .isGreaterThan(0);
            assertThat(p.droppedFor("sim-1"))
                    .isEqualTo(p.droppedSamples());

            sender.release.countDown();
        }

        @Test
        @Timeout(30)
        @DisplayName("drops are attributed to the simulation that lost them")
        void attributedPerSimulation() throws InterruptedException {
            RecordingSender sender = new RecordingSender();
            sender.release = new CountDownLatch(1);
            SimulationProperties properties = new SimulationProperties();
            properties.setTelemetryBufferSize(1000);
            TelemetryPublisher p = publisher(properties, sender);

            // One running, one finished, interleaved. The whole point of the per-run
            // counter is that a run's own record can say how much of its trace is
            // missing -- an aggregate across all runs cannot answer that.
            for (int i = 0; i < 4000; i++) {
                p.publish(sample(i % 2 == 0 ? "sim-a" : "sim-b", i));
            }

            long a = p.droppedFor("sim-a");
            long b = p.droppedFor("sim-b");

            assertThat(a).as("sim-a lost samples").isGreaterThan(0);
            assertThat(b).as("sim-b lost samples").isGreaterThan(0);
            assertThat(a + b)
                    .as("every drop is attributed to exactly one run")
                    .isEqualTo(p.droppedSamples());
            assertThat(p.droppedFor("sim-never-ran")).isZero();

            sender.release.countDown();
        }

        @Test
        @Timeout(30)
        @DisplayName("a sender that throws loses only its batch, and the drain thread survives")
        void senderFailureIsContained() throws InterruptedException {
            // Previously the drain loop caught only InterruptedException, so a producer
            // failure -- a closed producer, an oversized record, max.block.ms exceeded --
            // killed the thread. Nothing restarted it and nothing reported it: publish()
            // went on filling a bounded buffer, every sample from every later run was
            // dropped, and runs still reported COMPLETED because flush() runs on the
            // caller's thread. The only trace was a stack trace on stderr.
            RecordingSender sender = new RecordingSender();
            sender.explode = new IllegalStateException("producer closed");
            SimulationProperties properties = new SimulationProperties();
            TelemetryPublisher p = publisher(properties, sender);

            for (int i = 0; i < 10; i++) {
                p.publish(sample("sim-1", i));
            }
            p.flush();

            assertThat(p.isDrainAlive())
                    .as("a broker failure must not end telemetry for the rest of the process")
                    .isTrue();
            assertThat(p.droppedSamples()).isEqualTo(10);
            assertThat(p.droppedFor("sim-1")).isEqualTo(10);
            assertThat(p.publishedSamples()).isZero();
        }

        @Test
        @Timeout(30)
        @DisplayName("nothing is counted as published that the broker did not accept")
        void partialAcceptIsNotSuccess() throws InterruptedException {
            // The broker's answer used to be discarded outright, so a fifteen-second
            // stall produced dropped == 0 and published += batchSize: a complete-looking
            // trace that was missing entirely.
            RecordingSender sender = new RecordingSender();
            sender.acceptFromEachBatch = 7;
            SimulationProperties properties = new SimulationProperties();
            // One sample per batch, so "accept 7" can only ever accept one, and the drop
            // count is the number of samples rather than the number of unacknowledged
            // tail elements of an arbitrarily coalesced batch.
            properties.setTelemetryBatchSize(1);
            TelemetryPublisher p = publisher(properties, sender);

            for (int i = 0; i < 20; i++) {
                p.publish(sample("sim-1", i));
            }
            p.flush();

            assertSoftly(softly -> {
                softly.assertThat(sender.received).as("the broker accepted these").hasSize(20);
                softly.assertThat(p.publishedSamples())
                        .as("nothing the broker refused may be counted as published")
                        .isEqualTo(20);
                softly.assertThat(p.droppedSamples()).as("nothing was refused").isZero();
            });
        }

        @Test
        @Timeout(30)
        @DisplayName("a broker that acknowledges part of a batch loses exactly the rest")
        void partialBatchAcceptIsCounted() throws InterruptedException {
            // The broker's answer used to be discarded outright, so a fifteen-second stall
            // produced dropped == 0 and published += batchSize: a complete-looking trace
            // that was missing entirely.
            RecordingSender sender = new RecordingSender();
            sender.acceptFromEachBatch = 3;
            SimulationProperties properties = new SimulationProperties();
            properties.setTelemetryBatchSize(4);
            TelemetryPublisher p = publisher(properties, sender);

            for (int i = 0; i < 10; i++) {
                p.publish(sample("sim-1", i));
            }
            p.flush();

            // Compared against what the broker actually acknowledged rather than against a
            // predicted split. How the queue coalesces into batches is a scheduling detail,
            // and asserting a fixed published/dropped pair tests the scheduler instead of
            // the accounting.
            int accepted = sender.acceptedTotal.get();
            assertThat(accepted).as("some were accepted, some refused").isBetween(1, 9);

            assertSoftly(softly -> {
                softly.assertThat(p.publishedSamples())
                        .as("nothing the broker refused may be counted as published")
                        .isEqualTo(accepted);
                softly.assertThat(p.droppedSamples())
                        .as("exactly the refused tail of each batch")
                        .isEqualTo(10 - accepted);
                softly.assertThat(p.droppedFor("sim-1")).isEqualTo(10 - accepted);
                softly.assertThat(p.publishedSamples() + p.droppedSamples())
                        .as("every offered sample is accounted for exactly once")
                        .isEqualTo(10);
            });
        }
    }

    // ------------------------------------------------------------------ ordinary operation

    @Nested
    @DisplayName("ordinary publishing")
    class Normal {

        @Test
        @Timeout(30)
        @DisplayName("sends every sample to the telemetry topic, in emission order within a batch")
        void sendsEverything() throws InterruptedException {
            RecordingSender sender = new RecordingSender();
            TelemetryPublisher p = publisher(new SimulationProperties(), sender);

            List<String> expected = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                p.publish(sample("sim-1", i));
                expected.add("api");
            }
            p.flush();

            assertThat(sender.received).hasSize(200);
            assertThat(sender.received.stream().map(TelemetryEvent::nodeId).toList())
                    .isEqualTo(expected);
            assertThat(p.droppedSamples()).isZero();
            assertThat(p.publishedSamples()).isEqualTo(200);
        }

        @Test
        @Timeout(30)
        @DisplayName("batches, rather than sending one sample per round trip")
        void batches() throws InterruptedException {
            // A 6-node, 60-tick run emits ~3200 samples. One per send is 3200 round trips,
            // the simulation outruns the producer, the buffer grows, and the run is
            // declared timed out while the broker is perfectly healthy.
            RecordingSender sender = new RecordingSender();
            SimulationProperties properties = new SimulationProperties();
            properties.setTelemetryBatchSize(100);
            TelemetryPublisher p = publisher(properties, sender);

            for (int i = 0; i < 1000; i++) {
                p.publish(sample("sim-1", i));
            }
            p.flush();

            assertThat(sender.batches.get())
                    .as("fewer round trips than samples: 1000 samples, batches of at most 100")
                    .isLessThan(1000);
            assertThat(sender.received).hasSize(1000);
        }

        @Test
        @Timeout(30)
        @DisplayName("ignores a null sample instead of throwing at the tick loop")
        void nullIsIgnored() {
            TelemetryPublisher p = publisher(new SimulationProperties(), new RecordingSender());

            p.publish(null);

            assertThat(p.flush()).isZero();
        }

        @Test
        @Timeout(30)
        @DisplayName("two concurrent runs both get every sample delivered")
        void concurrentRunsBothDelivered() throws InterruptedException {
            // The old barrier was a shared buffer polled by both runs, so one run's flush
            // could drain the other's samples and neither run's ordering was actually
            // established. Both runs' traces must survive either run's flush.
            RecordingSender sender = new RecordingSender();
            TelemetryPublisher p = publisher(new SimulationProperties(), sender);

            Thread a = new Thread(() -> {
                for (int i = 0; i < 300; i++) {
                    p.publish(sample("sim-a", i));
                }
            });
            Thread b = new Thread(() -> {
                for (int i = 0; i < 300; i++) {
                    p.publish(sample("sim-b", i));
                }
            });
            a.start();
            b.start();
            a.join();
            b.join();

            p.flush();

            assertThat(sender.received.stream().filter(e -> e.simulationId().equals("sim-a")).count())
                    .isEqualTo(300);
            assertThat(sender.received.stream().filter(e -> e.simulationId().equals("sim-b")).count())
                    .isEqualTo(300);
            assertThat(p.droppedSamples()).isZero();
        }

        @Test
        @Timeout(30)
        @DisplayName("reports the drain thread as alive after a clean shutdown")
        void drainAliveAfterUse() {
            TelemetryPublisher p = publisher(new SimulationProperties(), new RecordingSender());
            p.publish(sample("sim-1", 1));
            p.flush();

            assertThat(p.isDrainAlive()).isTrue();
        }

        @Test
        @Timeout(30)
        @DisplayName("closes promptly even with a sender parked mid-batch")
        void closeDoesNotHang() throws InterruptedException {
            RecordingSender sender = new RecordingSender();
            SimulationProperties properties = new SimulationProperties();
            TelemetryPublisher p = publisher(properties, sender);
            sender.release = new CountDownLatch(1);

            for (int i = 0; i < 10; i++) {
                p.publish(sample("sim-1", i));
            }
            Thread.sleep(200);
            sender.release.countDown();

            Duration elapsed = Duration.ZERO;
            long start = System.nanoTime();
            p.close();
            elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(elapsed)
                    .as("close() is on the shutdown path and must not block the context")
                    .isLessThan(Duration.ofSeconds(10));
        }
    }
}