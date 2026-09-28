package com.archforge.ai.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Value
@Builder
public class EvaluationResult {
    String simulationId;
    String submissionId;
    String overallScore;
    String summary;
    List<String> strengths;
    List<String> weaknesses;
    List<String> recommendations;
    Map<String, Double> metrics;
    List<String> retrievedReferences;
    Instant evaluatedAt;
}
