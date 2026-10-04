package com.archforge.simulation.contract;

import com.archforge.contracts.event.SimulationLifecycleEvent;
import com.archforge.contracts.event.SimulationRequestedEvent;
import com.archforge.contracts.event.SimulationSummaryEvent;
import com.archforge.contracts.event.SubmissionLifecycleEvent;
import com.archforge.contracts.event.TelemetryEvent;
import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.json.ContractJson;
import com.archforge.contracts.lifecycle.MetricNames;
import com.archforge.contracts.lifecycle.ScenarioType;
import com.archforge.contracts.lifecycle.SimulationStatus;
import com.archforge.contracts.lifecycle.SubmissionStatus;
import com.archforge.simulation.support.TestGraphs;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * The engine's payloads survive a Jackson round trip.
 *
 * <h2>Why these tests exist at all</h2>
 * Every wire DTO used to be a Lombok {@code @Value @Builder} with no Jackson creator.
 * On the first record, Jackson threw
 * {@code InvalidDefinitionException: no Creators, like default constructor, exist}.
 * The consumer caught it, logged it, and returned — so the service stayed healthy, the
 * topic stayed empty, and the entire simulation pipeline was dead with no symptom other
 * than an absent log line. A healthy service is not evidence that it works.
 *
 * <p>These tests run the exact mapper the service uses. Serialisation is checked in the
 * producing service and deserialisation in the consuming one, so a test that only
 * serialises cannot see a contract that only fails on the way in — which is precisely
 * how that bug survived.
 */
class ContractSerializationTest {

    private final ObjectMapper mapper = ContractJson.create();

    @Test
    @DisplayName("a telemetry sample round-trips every field")
    void telemetryRoundTrip() throws Exception {
        TelemetryEvent original = TelemetryEvent.of(
                "sim-1", "sub-1", "db", com.archforge.contracts.canvas.NodeType.DATABASE,
                MetricNames.LATENCY_MS, 42.5, 7, "spike", true);

        TelemetryEvent parsed = mapper.readValue(
                mapper.writeValueAsString(original), TelemetryEvent.class);

        assertSoftly(softly -> {
            softly.assertThat(parsed.simulationId()).isEqualTo("sim-1");
            softly.assertThat(parsed.submissionId()).isEqualTo("sub-1");
            softly.assertThat(parsed.nodeId()).isEqualTo("db");
            softly.assertThat(parsed.nodeType()).isEqualTo("database");
            softly.assertThat(parsed.metricName()).isEqualTo(MetricNames.LATENCY_MS);
            softly.assertThat(parsed.value()).isEqualTo(42.5);
            softly.assertThat(parsed.unit()).isEqualTo("ms");
            softly.assertThat(parsed.tick()).isEqualTo(7);
            softly.assertThat(parsed.phase()).isEqualTo("spike");
            softly.assertThat(parsed.failureActive()).isTrue();
        });
    }

    @Test
    @DisplayName("an Instant is an ISO-8601 string, never epoch millis")
    void instantIsIso8601() throws Exception {
        // A double cannot hold nanoseconds at present-day epochs, and Kafka payloads
        // get read by humans during an incident. 1735689600000 in a log is not
        // something anyone can act on.
        TelemetryEvent event = TelemetryEvent.of(
                "sim-1", "sub-1", "api", null, MetricNames.LATENCY_MS, 1, 0, "warmup", false);

        String json = mapper.writeValueAsString(event);

        assertThat(json).contains("\"timestamp\":\"");
        assertThat(json).doesNotContain("\"timestamp\":1");
    }

    @Test
    @DisplayName("a simulation task carries its graph and its determinism inputs")
    void simulationTaskRoundTrip() throws Exception {
        SimulationRequestedEvent original = new SimulationRequestedEvent(
                "evt-1", "corr-1", "sim-1", "sub-1", "user-1", "sess-1",
                TestGraphs.readThroughCache(), "cache_failure",
                90, 1000, 12345L, java.util.Map.of("note", "hello"), Instant.now());

        SimulationRequestedEvent parsed = mapper.readValue(
                mapper.writeValueAsString(original), SimulationRequestedEvent.class);

        assertSoftly(softly -> {
            softly.assertThat(parsed.eventId()).isEqualTo("evt-1");
            softly.assertThat(parsed.correlationId()).isEqualTo("corr-1");
            softly.assertThat(parsed.simulationId()).isEqualTo("sim-1");
            softly.assertThat(parsed.submissionId()).isEqualTo("sub-1");
            softly.assertThat(parsed.userId()).isEqualTo("user-1");
            softly.assertThat(parsed.sessionId()).isEqualTo("sess-1");
            softly.assertThat(parsed.scenario()).isEqualTo("cache_failure");
            softly.assertThat(parsed.scenarioType()).isEqualTo(ScenarioType.CACHE_FAILURE);
            softly.assertThat(parsed.durationSeconds()).isEqualTo(90);
            softly.assertThat(parsed.tickMillis()).isEqualTo(1000);
            softly.assertThat(parsed.seed()).isEqualTo(12345L);
            softly.assertThat(parsed.tickCount()).isEqualTo(90);
            softly.assertThat(parsed.workloadOverrides()).containsEntry("note", "hello");
        });
    }

    @Test
    @DisplayName("the graph survives the round trip with its numbers intact")
    void graphRoundTripPreservesPhysics() throws Exception {
        // A graph that arrives with empty capacity maps is the exact failure the engine
        // cannot detect: every component would rate at the 1 rps floor and the run
        // would look like a very slow architecture rather than a broken payload.
        CompiledGraph original = TestGraphs.readThroughCache();

        CompiledGraph parsed = mapper.readValue(
                mapper.writeValueAsString(original), CompiledGraph.class);

        assertSoftly(softly -> {
            softly.assertThat(parsed.nodeCount()).isEqualTo(original.nodeCount());
            softly.assertThat(parsed.edgeCount()).isEqualTo(original.edgeCount());
            softly.assertThat(parsed.node("db")).isPresent();
            softly.assertThat(parsed.node("db").orElseThrow().getCapacity())
                    .as("database capacity")
                    .containsEntry("iops", 3000.0);
            softly.assertThat(parsed.node("db").orElseThrow().getLatencyProfile())
                    .as("database service time must stay readP50Ms, not p50Ms")
                    .containsEntry("readP50Ms", 5.0);
            softly.assertThat(parsed.node("cache").orElseThrow().getThroughput())
                    .as("the cache's rate lives in throughput; losing it rates the cache at 1 rps")
                    .containsEntry("operationsPerSecond", 50000.0);
            softly.assertThat(parsed.node("cache").orElseThrow().getSimulationMetadata())
                    .containsKey("properties");
            // readThroughCache is lb -> api -> cache -> db, so api's only
            // dependent is the cache, and the cache's dependent is db. Losing
            // either direction on the wire changes what the simulator routes:
            // arrivals come from incoming edges and failure blast radius comes
            // from dependencies, so a dropped index does not fail loudly -- it
            // silently starves the database.
            softly.assertThat(parsed.node("api").orElseThrow().getDependents())
                    .containsExactly("cache");
            softly.assertThat(parsed.node("cache").orElseThrow().getDependents())
                    .containsExactly("db");
            softly.assertThat(parsed.node("cache").orElseThrow().getDependencies())
                    .containsExactly("api");
            softly.assertThat(parsed.node("db").orElseThrow().getDependencies())
                    .containsExactly("cache");
            softly.assertThat(parsed.node("lb").orElseThrow().getDependencies()).isEmpty();
        });
    }

    @Test
    @DisplayName("the behavioural properties the simulator reads survive the round trip")
    void behaviouralPropertiesRoundTrip() throws Exception {
        CompiledGraph parsed = mapper.readValue(
                mapper.writeValueAsString(TestGraphs.readThroughCache()), CompiledGraph.class);

        Object properties = parsed.node("cache").orElseThrow()
                .getSimulationMetadata().get("properties");

        // Read outside the soft-assert lambda: a lambda body cannot propagate a
        // checked exception, so a mapper call inside one has to be wrapped and the
        // failure would surface as a confusing assertion error rather than as the
        // serialisation problem it is.
        Object externalProperties = mapper.readValue(
                        mapper.writeValueAsString(TestGraphs.deepChain()), CompiledGraph.class)
                .node("ext").orElseThrow().getSimulationMetadata().get("properties");

        assertSoftly(softly -> {
            softly.assertThat(properties).isInstanceOf(java.util.Map.class);
            softly.assertThat((java.util.Map<?, ?>) properties)
                    .as("the cache's declared hit rate")
                    .satisfies(map -> assertThat(map.get("hitRate")).isEqualTo(0.8));
            softly.assertThat((java.util.Map<?, ?>) externalProperties)
                    .as("timeoutMs arrives as an Integer, and a consumer assuming Double "
                            + "gets a ClassCastException and silently uses the default")
                    .satisfies(map -> assertThat(map.get("timeoutMs")).isEqualTo(5000));
        });
    }

    @Test
    @DisplayName("a lifecycle transition round-trips, including the architecture digest")
    void lifecycleRoundTrip() throws Exception {
        SimulationLifecycleEvent original = SimulationLifecycleEvent.of(
                "corr-1", "sim-1", "sub-1", ScenarioType.CACHE_FAILURE,
                SimulationStatus.COMPLETED, "Completed 60 ticks", 60, 60,
                TestGraphs.readThroughCache());

        SimulationLifecycleEvent parsed = mapper.readValue(
                mapper.writeValueAsString(original), SimulationLifecycleEvent.class);

        assertSoftly(softly -> {
            softly.assertThat(parsed.simulationId()).isEqualTo("sim-1");
            softly.assertThat(parsed.submissionId()).isEqualTo("sub-1");
            softly.assertThat(parsed.correlationId()).isEqualTo("corr-1");
            softly.assertThat(parsed.status()).isEqualTo(SimulationStatus.COMPLETED);
            softly.assertThat(parsed.status().getValue()).isEqualTo("completed");
            softly.assertThat(parsed.tickCount()).isEqualTo(60);
            softly.assertThat(parsed.totalTicks()).isEqualTo(60);
            softly.assertThat(parsed.scenarioType()).isEqualTo(ScenarioType.CACHE_FAILURE);
            softly.assertThat(parsed.architecture()).isNotNull();
            softly.assertThat(parsed.architecture().nodes()).hasSize(4);
            softly.assertThat(parsed.architecture().edges()).hasSize(3);
            softly.assertThat(parsed.architecture().nodes()).allSatisfy(digest -> {
                softly.assertThat(digest.id()).isNotBlank();
                softly.assertThat(digest.type()).isNotBlank();
            });
        });
    }

    @Test
    @DisplayName("a submission lifecycle transition round-trips")
    void submissionLifecycleRoundTrip() throws Exception {
        SubmissionLifecycleEvent original = SubmissionLifecycleEvent.withDetails(
                "corr-1", "sub-1", "sim-1", "user-1", "design-1",
                SubmissionLifecycleEvent.STAGE_SIMULATION, SubmissionStatus.SIMULATING,
                "Simulating cache outage for 60s",
                java.util.Map.of("ticks", 60L, "scenario", "cache_failure"));

        SubmissionLifecycleEvent parsed = mapper.readValue(
                mapper.writeValueAsString(original), SubmissionLifecycleEvent.class);

        assertSoftly(softly -> {
            softly.assertThat(parsed.submissionId()).isEqualTo("sub-1");
            softly.assertThat(parsed.simulationId()).isEqualTo("sim-1");
            softly.assertThat(parsed.stage()).isEqualTo("simulation-engine");
            softly.assertThat(parsed.status()).isEqualTo(SubmissionStatus.SIMULATING);
            softly.assertThat(parsed.details()).containsEntry("ticks", 60);
            softly.assertThat(parsed.eventId()).isNotBlank();
        });
    }

    @Test
    @DisplayName("a summary event round-trips, including the nested totals and digests")
    void summaryRoundTrip() throws Exception {
        SimulationSummaryEvent original = new SimulationSummaryEvent(
                "sim-1", "sub-1", "corr-1", "cache_failure",
                SimulationLifecycleEvent.ArchitectureDigest.of(TestGraphs.readThroughCache()),
                List.of(new SimulationSummaryEvent.FailureRecord(
                        "cache", "cache", "cache-miss-storm", 20, 45, true, 5000,
                        List.of("db"), 120.5, 0.02)),
                List.of(new SimulationSummaryEvent.NodeSummary(
                        // p50 p95 p99 max, avgError peakError, peakRps avgRps,
                        // peakUtil, avgHitRate, peakQueue, failureTarget, saturated, samples
                        "db", "database", "PostgreSQL", 5, 20, 85, 120, 0.001, 0.05,
                        400, 380, 0.8, -1, 12L, false, false, 60L)),
                new SimulationSummaryEvent.Totals(5, 25, 85, 0.002, 0.05, 400, 400,
                        24000, 48, 0, 1, 5000),
                List.of(), java.util.Map.of("criticalPathLength", 3L),
                Instant.now(), Instant.now(), 60, 60, 1000,
                List.of("partial trace: 400 of 3000 samples were dropped"));

        SimulationSummaryEvent parsed = mapper.readValue(
                mapper.writeValueAsString(original), SimulationSummaryEvent.class);

        assertSoftly(softly -> {
            softly.assertThat(parsed.simulationId()).isEqualTo("sim-1");
            softly.assertThat(parsed.scenarioType()).isEqualTo(ScenarioType.CACHE_FAILURE);
            softly.assertThat(parsed.failures()).hasSize(1);
            softly.assertThat(parsed.failures().get(0).nodeId()).isEqualTo("cache");
            softly.assertThat(parsed.failures().get(0).durationTicks()).isEqualTo(25);
            softly.assertThat(parsed.failures().get(0).directlyAffectedNodeIds())
                    .containsExactly("db");
            softly.assertThat(parsed.nodeSummaries()).hasSize(1);
            softly.assertThat(parsed.nodeSummaries().get(0).p99LatencyMs()).isEqualTo(85);
            softly.assertThat(parsed.nodeSummaries().get(0).label()).isEqualTo("PostgreSQL");
            softly.assertThat(parsed.totals().p99LatencyMs()).isEqualTo(85);
            softly.assertThat(parsed.totals().saturatedNodeCount()).isEqualTo(1);
            softly.assertThat(parsed.worstNodeP99()).isEqualTo(85);
            softly.assertThat(parsed.node("db")).isNotNull();
            softly.assertThat(parsed.graphMetrics()).containsKey("criticalPathLength");
        });

        // The caveat has to survive the wire. It is the one field that tells a reader the
        // percentiles above were computed from a fraction of the run, so an event that
        // dropped it on serialisation would be worse than one that never had it.
        assertThat(parsed.warnings())
                .as("a partial trace's warning must reach the consumer")
                .containsExactly("partial trace: 400 of 3000 samples were dropped");
    }

    @Test
    @DisplayName("a summary built with no warnings reads as no warnings")
    void absentWarningsMeansNone() {
        // Absent is not the same as "unverified". A summary that cannot say its trace was
        // complete must not default to claiming it was -- the default that made a partial
        // trace indistinguishable from a whole one.
        SimulationSummaryEvent summary = new SimulationSummaryEvent(
                "sim-2", "sub-2", "corr-2", "normal",
                null, List.of(), List.of(), null, List.of(), java.util.Map.of(),
                Instant.now(), Instant.now(), 60, 60, 1000, null);

        assertThat(summary.warnings()).isEmpty();
    }

    @Test
    @DisplayName("an unknown field from a newer producer is ignored, not fatal")
    void unknownFieldsAreTolerated() throws Exception {
        // Additive schema evolution must not break a deployed consumer. One service
        // disabling FAIL_ON_UNKNOWN_PROPERTIES and another not is how a field addition
        // breaks exactly the service nobody tested.
        String json = """
                {"simulationId":"sim-1","submissionId":"sub-1","nodeId":"db",
                 "nodeType":"database","metricName":"latency_ms","value":1.0,
                 "unit":"ms","tick":0,"phase":"warmup","failureActive":false,
                 "timestamp":"2026-01-01T00:00:00Z",
                 "aFieldFromTheFuture":{"nested":true}}
                """;

        TelemetryEvent parsed = mapper.readValue(json, TelemetryEvent.class);

        assertThat(parsed.simulationId()).isEqualTo("sim-1");
        assertThat(parsed.value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a malformed payload fails loudly rather than deserialising to null")
    void malformedPayloadFailsLoudly() throws Exception {
        // The consumer turns this into an IllegalArgumentException so the shared error
        // handler dead-letters it with a reason. A silently-null event would let the
        // listener return and commit the offset, losing the record with no trace.
        assertThatThrownBy(() -> mapper.readValue("{\"simulationId\":", TelemetryEvent.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);

        assertThatThrownBy(() -> mapper.readValue("not json at all", SimulationRequestedEvent.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    @DisplayName("a missing graph is a null field, so the engine can reject it explicitly")
    void missingGraphIsNull() throws Exception {
        String json = """
                {"eventId":"e","simulationId":"s","submissionId":"b",
                 "scenario":"normal","durationSeconds":60,"tickMillis":1000,"seed":1}
                """;

        SimulationRequestedEvent parsed = mapper.readValue(json, SimulationRequestedEvent.class);

        assertThat(parsed.graph()).isNull();
    }

    @Test
    @DisplayName("an out-of-range tick is floored rather than rejected")
    void tickIsFloored() throws Exception {
        // A tick below the floor would make a run's window arithmetic produce
        // hundreds of thousands of windows, which is a memory exhaustion bug reachable
        // from a single record.
        SimulationRequestedEvent task = new SimulationRequestedEvent(
                "e", "c", "s", "b", "u", null, TestGraphs.singleNode(),
                "normal", 60, 1L, 1L, java.util.Map.of(), Instant.now());

        assertThat(task.tickMillis())
                .isEqualTo(SimulationRequestedEvent.MIN_TICK_MILLIS);
    }
}
