package com.archforge.contracts.event;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.lifecycle.ScenarioType;
import com.archforge.contracts.lifecycle.SimulationStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A simulation lifecycle transition on {@code simulation.lifecycle}.
 *
 * <p>This is the signal that lets the platform know <em>when a run is
 * over</em>. The previous design triggered the AI evaluator after five
 * aggregated metrics happened to arrive, which meant evaluation fired mid-run on
 * partial data — or never fired at all for a small graph. Completion is now an
 * explicit fact, not an accident of message volume.
 *
 * <p>The {@code STARTED} transition also carries the architecture digest so the
 * aggregator can build the summary without a second round-trip to a database.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SimulationLifecycleEvent(
        String eventId,
        String correlationId,
        String simulationId,
        String submissionId,
        String scenario,
        SimulationStatus status,
        String message,
        long tickCount,
        long totalTicks,
        ArchitectureDigest architecture,
        Instant occurredAt) {

    public record ArchitectureDigest(
            String name,
            List<NodeDigest> nodes,
            List<EdgeDigest> edges,
            Map<String, Object> graphMetadata,
            List<String> warnings) {

        public record NodeDigest(String id, String type, String label) {
            public NodeDigest {
                if (type == null) {
                    type = NodeType.SERVICE.getValue();
                }
                if (label == null) {
                    label = id;
                }
            }

            public static NodeDigest of(com.archforge.contracts.graph.CompiledNode node) {
                return new NodeDigest(node.getId(), node.getType().getValue(), node.getLabel());
            }
        }

        public record EdgeDigest(String id, String source, String target, double latencyMs, double bandwidthMbps) {

            public static EdgeDigest of(com.archforge.contracts.graph.CompiledEdge edge) {
                return new EdgeDigest(edge.getId(), edge.getSource(), edge.getTarget(),
                        edge.getLatencyMs(), edge.getBandwidthMbps());
            }
        }

        public static ArchitectureDigest of(com.archforge.contracts.graph.CompiledGraph graph) {
            if (graph == null) {
                return null;
            }
            return new ArchitectureDigest(
                    graph.getName(),
                    graph.getNodes().stream().map(NodeDigest::of).toList(),
                    graph.getEdges().stream().map(EdgeDigest::of).toList(),
                    graph.getGraphMetadata(),
                    graph.getWarnings());
        }
    }

    public SimulationLifecycleEvent {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
        if (status == null) {
            status = SimulationStatus.RUNNING;
        }
    }

    public ScenarioType scenarioType() {
        ScenarioType resolved = ScenarioType.findByValue(scenario);
        return resolved == null ? ScenarioType.NORMAL : resolved;
    }

    public static SimulationLifecycleEvent of(String correlationId,
                                              String simulationId,
                                              String submissionId,
                                              ScenarioType scenario,
                                              SimulationStatus status,
                                              String message,
                                              long tickCount,
                                              long totalTicks,
                                              com.archforge.contracts.graph.CompiledGraph graph) {
        return new SimulationLifecycleEvent(UUID.randomUUID().toString(), correlationId,
                simulationId, submissionId,
                (scenario == null ? ScenarioType.NORMAL : scenario).getValue(),
                status, message, tickCount, totalTicks,
                ArchitectureDigest.of(graph), Instant.now());
    }
}
