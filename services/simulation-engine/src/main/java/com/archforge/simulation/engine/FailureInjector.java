package com.archforge.simulation.engine;

import com.archforge.simulation.model.SimulationTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class FailureInjector {

    private final Random random = new Random();

    public List<FailureScenario> generateFailures(SimulationTask task, String scenarioType) {
        if (task == null || task.getCompiledGraph() == null) {
            return Collections.emptyList();
        }

        return switch (scenarioType) {
            case "SERVICE_FAILURE" -> generateServiceFailure(task);
            case "CACHE_FAILURE" -> generateCacheFailure(task);
            case "DATABASE_OVERLOAD" -> generateDatabaseOverload(task);
            case "KAFKA_CONSUMER_FAILURE" -> generateKafkaConsumerFailure(task);
            case "NETWORK_DEGRADATION" -> generateNetworkDegradation(task);
            default -> Collections.emptyList();
        };
    }

    private List<FailureScenario> generateServiceFailure(SimulationTask task) {
        List<FailureScenario> failures = new ArrayList<>();
        List<String> serviceNodes = findNodesByType(task, "SERVICE");

        if (!serviceNodes.isEmpty()) {
            String targetNode = serviceNodes.get(random.nextInt(serviceNodes.size()));
            failures.add(new FailureScenario(
                    "SERVICE_FAILURE",
                    targetNode,
                    10,
                    15,
                    "Service " + targetNode + " is down"
            ));
        }
        return failures;
    }

    private List<FailureScenario> generateCacheFailure(SimulationTask task) {
        List<FailureScenario> failures = new ArrayList<>();
        List<String> cacheNodes = findNodesByType(task, "CACHE");

        if (!cacheNodes.isEmpty()) {
            String targetNode = cacheNodes.get(random.nextInt(cacheNodes.size()));
            failures.add(new FailureScenario(
                    "CACHE_FAILURE",
                    targetNode,
                    10,
                    20,
                    "Cache " + targetNode + " is unavailable"
            ));
        }
        return failures;
    }

    private List<FailureScenario> generateDatabaseOverload(SimulationTask task) {
        List<FailureScenario> failures = new ArrayList<>();
        List<String> dbNodes = findNodesByType(task, "DATABASE");

        if (!dbNodes.isEmpty()) {
            String targetNode = dbNodes.get(random.nextInt(dbNodes.size()));
            failures.add(new FailureScenario(
                    "DATABASE_OVERLOAD",
                    targetNode,
                    10,
                    20,
                    "Database " + targetNode + " is overloaded"
            ));
        }
        return failures;
    }

    private List<FailureScenario> generateKafkaConsumerFailure(SimulationTask task) {
        List<FailureScenario> failures = new ArrayList<>();
        List<String> queueNodes = findNodesByType(task, "QUEUE");

        if (!queueNodes.isEmpty()) {
            String targetNode = queueNodes.get(random.nextInt(queueNodes.size()));
            failures.add(new FailureScenario(
                    "KAFKA_CONSUMER_FAILURE",
                    targetNode,
                    10,
                    20,
                    "Kafka consumer for " + targetNode + " has stalled"
            ));
        }
        return failures;
    }

    private List<FailureScenario> generateNetworkDegradation(SimulationTask task) {
        List<FailureScenario> failures = new ArrayList<>();
        List<String> allNodes = new ArrayList<>();

        if (task.getCompiledGraph() != null && task.getCompiledGraph().getNodes() != null) {
            task.getCompiledGraph().getNodes().forEach(n -> allNodes.add(n.getId()));
        }

        if (!allNodes.isEmpty()) {
            String targetNode = allNodes.get(random.nextInt(allNodes.size()));
            failures.add(new FailureScenario(
                    "NETWORK_DEGRADATION",
                    targetNode,
                    10,
                    20,
                    "Network degradation detected at " + targetNode
            ));
        }
        return failures;
    }

    private List<String> findNodesByType(SimulationTask task, String nodeType) {
        if (task.getCompiledGraph() == null || task.getCompiledGraph().getNodes() == null) {
            return Collections.emptyList();
        }
        return task.getCompiledGraph().getNodes().stream()
                .filter(n -> n.getType() != null && n.getType().name().equalsIgnoreCase(nodeType))
                .map(n -> n.getId())
                .toList();
    }

    public static boolean isFailureActive(FailureScenario failure, int elapsedSeconds) {
        return elapsedSeconds >= failure.startSecond() && elapsedSeconds < failure.startSecond() + failure.durationSeconds();
    }

    public List<String> getAffectedNodes(FailureScenario failure, SimulationTask task) {
        List<String> affected = new ArrayList<>();
        affected.add(failure.nodeId());

        if (task.getCompiledGraph() != null && task.getCompiledGraph().getNodes() != null) {
            for (var node : task.getCompiledGraph().getNodes()) {
                if (node.getDependents() != null && node.getDependents().contains(failure.nodeId())) {
                    affected.add(node.getId());
                }
            }
        }

        return affected;
    }

    public record FailureScenario(String type, String nodeId, int startSecond, int durationSeconds, String description) {}
}
