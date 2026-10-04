package com.archforge.graphcompiler.validation;

import com.archforge.contracts.canvas.Topology;
import com.archforge.graphcompiler.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Runs every validator in a fixed order and aggregates the findings.
 *
 * <p><b>Ordering is deliberate.</b> Structural checks run first because they decide
 * whether the graph can be analysed at all, and cycle detection runs after them for
 * the same reason — a cycle message is meaningless if it names nodes that do not
 * exist. Design-level warnings run last, because they are only useful on a graph that
 * is structurally sound.
 *
 * <p><b>A defect this fixes:</b> the previous version threw
 * {@link ValidationException} as soon as any error appeared, which discarded the
 * warnings collected so far. A user with one cycle <em>and</em> two orphan components
 * fixed the cycle, resubmitted, and only then learned about the orphans — one
 * submission per problem. All blocking and non-blocking findings are now collected and
 * returned together, so a single round-trip reports everything wrong with the design.
 */
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

    /**
     * @return every finding, blocking and otherwise
     * @throws ValidationException when at least one finding is blocking; the exception
     *         carries the blocking messages, and the {@link ValidationResult} is logged
     *         with the full set
     */
    public ValidationResult validate(Topology topology) {
        ValidationResult result = new ValidationResult();

        // 1. Structure: null, empty, size limits, duplicate ids, dangling parents.
        merge(result, schemaValidator.validate(topology));
        merge(result, nodeTypeValidator.validate(topology));
        merge(result, edgeValidator.validate(topology));

        if (result.hasErrors()) {
            // A cycle report naming non-existent nodes would be noise; stop here.
            return throwIfBlocking(result, topology);
        }

        // 2. Acyclicity.
        List<List<String>> cycles = cycleDetector.detectCycles(topology);
        for (List<String> cycle : cycles) {
            result.addError("topology", "CYCLE_DETECTED",
                    "Cycle detected: " + String.join(" -> ", cycle)
                            + ". Remove the circular dependency so the topology is a valid DAG.",
                    ValidationError.Severity.ERROR);
        }

        if (result.hasErrors()) {
            return throwIfBlocking(result, topology);
        }

        // 3. Design quality. Warnings only — these are what the user came here to find.
        merge(result, orphanNodeDetector.validate(topology));
        merge(result, semanticValidator.validate(topology));

        log.debug("Validation completed: {} error(s), {} warning(s), {} note(s)",
                result.errorCount(), result.warningCount(),
                result.getFindings().size() - result.errorCount() - result.warningCount());

        return throwIfBlocking(result, topology);
    }

    /**
     * @return the accumulated result when nothing is blocking
     * @throws ValidationException carrying field and code attribution for every blocking
     *         finding, so the client can point at the offending node instead of parsing
     *         prose
     */
    private ValidationResult throwIfBlocking(ValidationResult result, Topology topology) {
        if (!result.hasErrors()) {
            return result;
        }

        List<ValidationException.ValidationFinding> blocking = result.getFindings().stream()
                .filter(finding -> finding.getSeverity() == ValidationError.Severity.ERROR)
                .map(finding -> new ValidationException.ValidationFinding(
                        finding.getField(), finding.getCode(), finding.getMessage()))
                .toList();

        log.info("Topology rejected with {} blocking finding(s): {}", blocking.size(),
                blocking.stream().map(ValidationException.ValidationFinding::message).toList());

        // Keep the non-blocking findings visible in the log even though the caller only
        // receives the blocking ones, so an operator sees the whole picture.
        if (result.hasWarnings()) {
            log.info("Suppressed alongside the blocking findings: {}", result.getWarningMessages());
        }

        throw ValidationException.fromFindings(blocking);
    }

    private void merge(ValidationResult target, ValidationResult source) {
        for (ValidationError error : source.getFindings()) {
            target.addError(error);
        }
    }
}
