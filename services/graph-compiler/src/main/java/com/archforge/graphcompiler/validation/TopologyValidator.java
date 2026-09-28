package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.exception.DAGCycleException;
import com.archforge.graphcompiler.exception.ValidationException;
import com.archforge.graphcompiler.schema.ReactFlowTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    public void validate(ReactFlowTopology topology) {
        log.debug("Starting topology validation");

        ValidationResult result = new ValidationResult();

        ValidationResult schemaResult = schemaValidator.validate(topology);
        mergeResults(result, schemaResult);

        if (result.hasErrors()) {
            throw new ValidationException(result.getErrorMessages());
        }

        ValidationResult nodeTypeResult = nodeTypeValidator.validate(topology);
        mergeResults(result, nodeTypeResult);

        ValidationResult edgeResult = edgeValidator.validate(topology);
        mergeResults(result, edgeResult);

        if (result.hasErrors()) {
            throw new ValidationException(result.getErrorMessages());
        }

        try {
            cycleDetector.detectCycles(topology);
        } catch (DAGCycleException e) {
            throw new ValidationException(e.getMessage());
        }

        ValidationResult orphanResult = orphanNodeDetector.validate(topology);
        mergeResults(result, orphanResult);

        if (result.hasErrors()) {
            throw new ValidationException(result.getErrorMessages());
        }

        log.debug("Topology validation completed with {} warning(s)", result.getErrors().size());
    }

    private void mergeResults(ValidationResult target, ValidationResult source) {
        for (ValidationError error : source.getErrors()) {
            target.addError(error);
        }
    }
}
