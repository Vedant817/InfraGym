package com.archforge.ai.service;

import com.archforge.ai.model.AggregatedMetricsEvent;
import com.archforge.ai.model.EvaluationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArchitectureEvaluationService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Value("${ai.kafka.topics.evaluation-results:evaluation.results}")
    private String evaluationResultsTopic;

    @Value("${ai.rag.max-results:5}")
    private int maxRagResults;

    public EvaluationResult evaluate(String simulationId, String submissionId,
                                      List<AggregatedMetricsEvent> metrics) {
        log.info("Starting evaluation for simulation: {}", simulationId);

        String architectureSummary = buildArchitectureSummary(metrics);
        String metricsSummary = buildMetricsSummary(metrics);

        List<String> references = retrieveRelevantKnowledge(architectureSummary);

        String prompt = buildEvaluationPrompt(architectureSummary, metricsSummary, references);

        try {
            ChatResponse response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .chatResponse();

            String evaluationText = response.getResult().getOutput().getText();

            EvaluationResult result = parseEvaluationResponse(simulationId, submissionId,
                    evaluationText, metrics, references);

            publishResult(result);

            log.info("Evaluation completed for simulation: {}", simulationId);
            return result;
        } catch (Exception e) {
            log.error("Evaluation failed for simulation: {}", simulationId, e);
            throw new RuntimeException("Evaluation failed", e);
        }
    }

    private String buildArchitectureSummary(List<AggregatedMetricsEvent> metrics) {
        Map<String, Set<String>> nodeMetrics = new HashMap<>();
        for (AggregatedMetricsEvent event : metrics) {
            nodeMetrics.computeIfAbsent(event.getNodeId(), k -> new HashSet<>())
                    .add(event.getMetricName());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Architecture with ").append(nodeMetrics.size()).append(" nodes:\n");
        for (Map.Entry<String, Set<String>> entry : nodeMetrics.entrySet()) {
            sb.append("- Node ").append(entry.getKey())
                    .append(" with metrics: ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    private String buildMetricsSummary(List<AggregatedMetricsEvent> metrics) {
        Map<String, List<Double>> metricValues = new HashMap<>();
        for (AggregatedMetricsEvent event : metrics) {
            metricValues.computeIfAbsent(event.getMetricName(), k -> new ArrayList<>())
                    .add(event.getAvg());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Simulation Metrics Summary:\n");
        for (Map.Entry<String, List<Double>> entry : metricValues.entrySet()) {
            double avg = entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double max = entry.getValue().stream().mapToDouble(Double::doubleValue).max().orElse(0);
            sb.append("- ").append(entry.getKey()).append(": avg=").append(String.format("%.2f", avg))
                    .append(", max=").append(String.format("%.2f", max)).append("\n");
        }
        return sb.toString();
    }

    private List<String> retrieveRelevantKnowledge(String query) {
        try {
            return vectorStore.similaritySearch(
                    org.springframework.ai.vectorstore.SearchRequest.builder()
                            .query(query)
                            .topK(maxRagResults)
                            .build()
            ).stream()
                    .map(doc -> doc.getText())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("RAG retrieval failed, continuing without references", e);
            return Collections.emptyList();
        }
    }

    private String buildEvaluationPrompt(String architectureSummary, String metricsSummary,
                                          List<String> references) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a senior distributed systems architect evaluating a system design.\n\n");
        sb.append("## Architecture\n").append(architectureSummary).append("\n\n");
        sb.append("## Simulation Results\n").append(metricsSummary).append("\n\n");

        if (!references.isEmpty()) {
            sb.append("## Reference Architectures\n");
            for (String ref : references) {
                sb.append("- ").append(ref).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## Evaluation Criteria\n");
        sb.append("1. Identify the primary bottleneck based on simulation metrics\n");
        sb.append("2. Assess fault tolerance and redundancy\n");
        sb.append("3. Evaluate scalability under load\n");
        sb.append("4. Identify single points of failure\n");
        sb.append("5. Provide specific, actionable recommendations\n\n");
        sb.append("## Response Format\n");
        sb.append("Provide your evaluation in the following format:\n");
        sb.append("SCORE: [0-100]\n");
        sb.append("SUMMARY: [2-3 sentence overall assessment]\n");
        sb.append("STRENGTHS: [comma-separated list]\n");
        sb.append("WEAKNESSES: [comma-separated list]\n");
        sb.append("RECOMMENDATIONS: [comma-separated list]\n");

        return sb.toString();
    }

    private EvaluationResult parseEvaluationResponse(String simulationId, String submissionId,
                                                       String response, List<AggregatedMetricsEvent> metrics,
                                                       List<String> references) {
        String score = "50";
        String summary = "Evaluation completed.";
        List<String> strengths = new ArrayList<>();
        List<String> weaknesses = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        for (String line : response.split("\n")) {
            if (line.startsWith("SCORE:")) {
                score = line.substring(6).trim();
            } else if (line.startsWith("SUMMARY:")) {
                summary = line.substring(8).trim();
            } else if (line.startsWith("STRENGTHS:")) {
                strengths = parseList(line.substring(11).trim());
            } else if (line.startsWith("WEAKNESSES:")) {
                weaknesses = parseList(line.substring(11).trim());
            } else if (line.startsWith("RECOMMENDATIONS:")) {
                recommendations = parseList(line.substring(16).trim());
            }
        }

        Map<String, Double> metricsMap = new HashMap<>();
        for (AggregatedMetricsEvent event : metrics) {
            metricsMap.put(event.getMetricName(), event.getAvg());
        }

        return EvaluationResult.builder()
                .simulationId(simulationId)
                .submissionId(submissionId)
                .overallScore(score)
                .summary(summary)
                .strengths(strengths)
                .weaknesses(weaknesses)
                .recommendations(recommendations)
                .metrics(metricsMap)
                .retrievedReferences(references)
                .evaluatedAt(Instant.now())
                .build();
    }

    private List<String> parseList(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private void publishResult(EvaluationResult result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            kafkaTemplate.send(evaluationResultsTopic, result.getSimulationId(), json);
            log.debug("Published evaluation result for simulation: {}", result.getSimulationId());
        } catch (Exception e) {
            log.error("Failed to publish evaluation result", e);
        }
    }
}
