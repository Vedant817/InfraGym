package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.exception.DAGCycleException;
import com.archforge.graphcompiler.exception.ValidationException;
import com.archforge.graphcompiler.schema.ReactFlowTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TopologyValidator {

    private final SchemaValidator schemaValidator;
    private final NodeTypeValidator nodeTypeValidator;
    private final EdgeValidator edgeValidator;
    private final CycleDetector cycleDetector;
    private final OrphanNodeDetector orphanNodeDetector;
    private final SemanticValidator semanticValidator;

    public ValidationResult validate(ReactFlowTopology topology) {
        log.debug("Starting topology validation");

        ValidationResult result = new ValidationResult();

        mergeResults(result, schemaValidator.validate(topology));
        mergeResults(result, nodeTypeValidator.validate(topology));
        mergeResults(result, edgeValidator.validate(topology));

        if (result.hasErrors()) {
            throw new ValidationException(result.getErrorMessages());
        }

        List<List<String>> cycles = cycleDetector.detectCycles(topology);
        if (!cycles.isEmpty()) {
            for (List<String> cycle : cycles) {
                result.addError("topology", "CYCLE_DETECTED",
                        "Cycle detected in topology: " + String.join(" -> ", cycle) + ". Remove the circular dependency to make the topology a valid DAG.",
                        ValidationError.Severity.ERROR);
            }
            throw new ValidationException(result.getErrorMessages());
        }

        mergeResults(result, orphanNodeDetector.validate(topology));
        mergeResults(result, semanticValidator.validate(topology));

        if (result.hasErrors()) {
            throw new ValidationException(result.getErrorMessages());
        }

        log.debug("Topology validation completed with {} warning(s)", result.getErrors().size());
        return result;
    }

    private void mergeResults(ValidationResult target, ValidationResult source) {
        for (ValidationError error : source.getErrors()) {
            target.addError(error);
        }
    }
}
