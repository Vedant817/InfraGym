package com.archforge.ai.service;

import com.archforge.ai.analysis.ArchitectureAnalysis;
import com.archforge.ai.analysis.DeterministicEvaluator;
import com.archforge.ai.config.EvaluatorProperties;
import com.archforge.contracts.event.SimulationSummaryEvent;
import org.springframework.stereotype.Component;

/**
 * Runs the deterministic analysis with this instance's configured thresholds.
 *
 * <p>Exists so {@link ArchitectureEvaluationService} depends on a narrow role rather than on
 * the evaluator class directly: the service is about orchestration and publishing, the
 * evaluator is about arithmetic, and keeping them separate means the arithmetic can be tested
 * with no Spring context at all.
 *
 * <p>Thresholds come from configuration on every call rather than being captured in a
 * constructor. They are read once per evaluation rather than per sample, so the cost is
 * irrelevant, and it means a configuration change takes effect without a rebuild.
 */
@Component
public class DeterministicAnalysisRunner {

    private final EvaluatorProperties properties;

    public DeterministicAnalysisRunner(EvaluatorProperties properties) {
        this.properties = properties;
    }

    public ArchitectureAnalysis analyse(SimulationSummaryEvent summary) {
        return new DeterministicEvaluator(properties.getThresholds()).analyse(summary);
    }
}