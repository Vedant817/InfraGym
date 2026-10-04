package com.archforge.simulation.engine;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.graph.CompiledNode;
import com.archforge.simulation.support.TestGraphs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a node's declared characteristics are resolved.
 *
 * <p>Every test here exists because a specific answer was wrong before. The class
 * exists at all because two components needed the same lookup and their copies had
 * already drifted: the workload generator read only {@code capacity}, the simulator
 * read {@code capacity} then {@code throughput}, and both hard-coded 1000 when nothing
 * matched.
 */
class NodeCharacteristicsTest {

    @Nested
    @DisplayName("sustained rate")
    class SustainedRate {

        @Test
        @DisplayName("reads throughput as well as capacity")
        void readsThroughputMap() {
            // The defect: a cache publishes operationsPerSecond in throughput. Reading
            // only capacity returned the 1 rps floor, so every cache-outage scenario
            // was simulated against a fictitious one-request-per-second Redis.
            NodeCharacteristics cache = NodeCharacteristics.of(TestGraphs.cache("cache"));

            assertThat(cache.sustainedRateRps()).isEqualTo(50000);
            assertThat(cache.declaresThroughput()).isTrue();
        }

        @Test
        @DisplayName("reads capacity when that is where the figure lives")
        void readsCapacityMap() {
            assertThat(NodeCharacteristics.of(TestGraphs.loadBalancer("lb")).sustainedRateRps())
                    .isEqualTo(50000);
            assertThat(NodeCharacteristics.of(TestGraphs.service("api")).sustainedRateRps())
                    .isEqualTo(1000);
        }

        @Test
        @DisplayName("storage is capped by its IOPS ceiling, not only its query rate")
        void storageIsCappedByIops() {
            // DATABASE declares queriesPerSecond 5000 and iops 3000. A disk cannot
            // serve 5000 queries/second on 3000 IOPS, so the physical limit wins.
            NodeCharacteristics db = NodeCharacteristics.of(TestGraphs.database("db"));

            assertThat(db.sustainedRateRps()).isEqualTo(3000);
        }

        @Test
        @DisplayName("a low IOPS figure actually lowers the rating")
        void lowIopsLowersRating() {
            CompiledNode slowStore = TestGraphs.builder("db", NodeType.DATABASE, 0)
                    .capacity("iops", 200)
                    .throughput("queriesPerSecond", 5000)
                    .build();

            assertThat(NodeCharacteristics.of(slowStore).sustainedRateRps()).isEqualTo(200);
        }

        @Test
        @DisplayName("a rate key below the IOPS ceiling is not overridden upward")
        void iopsIsACeilingNotAFloor() {
            CompiledNode fastQueriesOnSlowDisk = TestGraphs.builder("db", NodeType.DATABASE, 0)
                    .capacity("iops", 5000)
                    .throughput("queriesPerSecond", 800)
                    .build();

            assertThat(NodeCharacteristics.of(fastQueriesOnSlowDisk).sustainedRateRps()).isEqualTo(800);
        }

        @Test
        @DisplayName("prefers the read path over the write path on a database")
        void prefersQueriesPerSecond() {
            // Both are declared. queriesPerSecond is listed first, so an OLTP design is
            // sized by the path it actually takes.
            assertThat(NodeCharacteristics.of(TestGraphs.database("db")).sustainedRateRps())
                    .isEqualTo(3000);
        }

        @Test
        @DisplayName("a queue is rated by messages per second")
        void queueRate() {
            assertThat(NodeCharacteristics.of(TestGraphs.queue("q")).sustainedRateRps())
                    .isEqualTo(10000);
        }

        @Test
        @DisplayName("falls back to the declared floor, and admits it")
        void undeclaredRateUsesFloor() {
            NodeCharacteristics bare = NodeCharacteristics.of(
                    TestGraphs.bare("api", NodeType.SERVICE));

            assertThat(bare.declaresThroughput()).isFalse();
            assertThat(bare.sustainedRateRps()).isEqualTo(NodeCharacteristics.UNDECLARED_RATE_RPS);
        }

        @Test
        @DisplayName("never returns zero or a negative rate")
        void neverReturnsNonPositive() {
            for (NodeType type : NodeType.values()) {
                double rate = NodeCharacteristics.of(
                        TestGraphs.builder("n", type, 0).build()).sustainedRateRps();
                assertThat(rate)
                        .as("rate for %s", type)
                        .isGreaterThanOrEqualTo(1.0);
            }
        }

        @Test
        @DisplayName("a zero or negative declared rate is treated as undeclared")
        void ignoresNonPositiveRates() {
            CompiledNode zeroRate = TestGraphs.builder("api", NodeType.SERVICE, 0)
                    .capacity("requestsPerSecond", 0)
                    .build();

            assertThat(NodeCharacteristics.of(zeroRate).declaresThroughput()).isFalse();
        }
    }

    @Nested
    @DisplayName("service time")
    class ServiceTime {

        @Test
        @DisplayName("a database uses readP50Ms, not p50Ms")
        void databaseUsesReadLatency() {
            // The defect: reading only p50Ms left a database -- which declares
            // readP50Ms/writeP50Ms and no p50Ms at all -- on the hard-coded 20 ms
            // fallback, so every design's most interesting component reported a
            // default rather than its own figure.
            NodeCharacteristics db = NodeCharacteristics.of(TestGraphs.database("db"));

            assertThat(db.baseLatencyMs()).isEqualTo(5);
            assertThat(db.declaresLatency()).isTrue();
        }

        @Test
        @DisplayName("a worker uses processingTimeMs")
        void workerUsesProcessingTime() {
            assertThat(NodeCharacteristics.of(TestGraphs.worker("w")).baseLatencyMs()).isEqualTo(100);
        }

        @Test
        @DisplayName("a service uses p50Ms")
        void serviceUsesP50() {
            assertThat(NodeCharacteristics.of(TestGraphs.service("api")).baseLatencyMs()).isEqualTo(20);
        }

        @Test
        @DisplayName("a cache uses p50Ms")
        void cacheUsesP50() {
            assertThat(NodeCharacteristics.of(TestGraphs.cache("cache")).baseLatencyMs()).isEqualTo(1);
        }

        @Test
        @DisplayName("falls back to the declared floor, and admits it")
        void undeclaredLatencyUsesFloor() {
            NodeCharacteristics bare = NodeCharacteristics.of(
                    TestGraphs.bare("api", NodeType.SERVICE));

            assertThat(bare.declaresLatency()).isFalse();
            assertThat(bare.baseLatencyMs()).isEqualTo(NodeCharacteristics.UNDECLARED_LATENCY_MS);
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("is read from the capacity map")
        void readFromCapacity() {
            NodeCharacteristics worker = NodeCharacteristics.of(TestGraphs.worker("w"));

            assertThat(worker.concurrency()).isEqualTo(10);
            assertThat(worker.hasConcurrencyLimit()).isTrue();
        }

        @Test
        @DisplayName("is absent, not zero, when undeclared")
        void absentWhenUndeclared() {
            NodeCharacteristics service = NodeCharacteristics.of(TestGraphs.service("api"));

            assertThat(service.concurrency()).isZero();
            assertThat(service.hasConcurrencyLimit()).isFalse();
        }
    }

    @Nested
    @DisplayName("behavioural properties")
    class Properties {

        @Test
        @DisplayName("a declared hit rate is read from the enrichment blob")
        void readsHitRate() {
            assertThat(NodeCharacteristics.of(TestGraphs.cache("cache")).hitRate())
                    .isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("an integer timeout is read, not cast away")
        void readsIntegerTimeout() {
            // EXTERNAL declares timeoutMs as the integer 5000, not a double. A
            // consumer that assumed Double got a ClassCastException and fell back to
            // the hard-coded default, so timeouts were always 5000 ms whatever the
            // design said.
            assertThat(NodeCharacteristics.of(TestGraphs.external("ext")).timeoutMs())
                    .isEqualTo(5000);
        }

        @Test
        @DisplayName("a double hit rate survives the round trip")
        void readsDoubleProperty() {
            assertThat(NodeCharacteristics.of(TestGraphs.cache("cache")).hitRate()).isNotEqualTo(0.7);
        }

        @Test
        @DisplayName("a missing property falls back rather than throwing")
        void missingPropertyFallsBack() {
            assertThat(NodeCharacteristics.of(TestGraphs.service("api")).hitRate())
                    .isEqualTo(NodeCharacteristics.DEFAULT_HIT_RATE);
            assertThat(NodeCharacteristics.of(TestGraphs.service("api")).timeoutMs())
                    .isEqualTo(NodeCharacteristics.DEFAULT_TIMEOUT_MS);
        }

        @Test
        @DisplayName("a queue bound is read from queueSize")
        void readsQueueBound() {
            assertThat(NodeCharacteristics.of(TestGraphs.worker("w")).queueBound()).isEqualTo(1000);
        }

        @Test
        @DisplayName("an unbounded queue reports unbounded, not zero")
        void unboundedQueue() {
            assertThat(NodeCharacteristics.of(TestGraphs.service("api")).queueBound())
                    .isEqualTo(Double.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("failure semantics by role")
    class Roles {

        @Test
        @DisplayName("only a cache passes its load through when it fails")
        void cacheIsTheOnlyPassThrough() {
            assertThat(NodeCharacteristics.of(TestGraphs.cache("cache")).passesThroughOnFailure()).isTrue();

            assertThat(NodeCharacteristics.of(TestGraphs.service("api")).passesThroughOnFailure()).isFalse();
            assertThat(NodeCharacteristics.of(TestGraphs.database("db")).passesThroughOnFailure()).isFalse();
            assertThat(NodeCharacteristics.of(TestGraphs.queue("q")).passesThroughOnFailure()).isFalse();
        }

        @Test
        @DisplayName("a queue absorbs a burst; a synchronous component rejects it")
        void bufferingIsDerivedFromRole() {
            assertThat(NodeCharacteristics.of(TestGraphs.queue("q")).buffers()).isTrue();
            assertThat(NodeCharacteristics.of(TestGraphs.cache("cache")).buffers()).isTrue();

            assertThat(NodeCharacteristics.of(TestGraphs.service("api")).buffers()).isFalse();
            assertThat(NodeCharacteristics.of(TestGraphs.database("db")).buffers()).isFalse();
        }

        @Test
        @DisplayName("a caller blocks on a fail-stop component but not on a stalled consumer")
        void callerBlocking() {
            assertThat(NodeCharacteristics.of(TestGraphs.service("api")).callerBlocksOnFailure()).isTrue();
            assertThat(NodeCharacteristics.of(TestGraphs.database("db")).callerBlocksOnFailure()).isTrue();
            // A producer's enqueue still succeeds when consumers stall; the backlog is
            // the signal, which is exactly why consumer lag is invisible to producers.
            assertThat(NodeCharacteristics.of(TestGraphs.queue("q")).callerBlocksOnFailure()).isFalse();
        }

        @Test
        @DisplayName("a cache is not reported as storage utilisation")
        void cacheIsExcludedFromStorageMetrics() {
            assertThat(NodeCharacteristics.typeIsStorage(NodeType.DATABASE)).isTrue();
            assertThat(NodeCharacteristics.typeIsStorage(NodeType.STORAGE)).isTrue();
            assertThat(NodeCharacteristics.typeIsStorage(NodeType.CACHE)).isFalse();
        }
    }
}
