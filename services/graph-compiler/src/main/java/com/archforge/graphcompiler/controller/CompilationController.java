package com.archforge.graphcompiler.controller;

import com.archforge.contracts.canvas.Topology;
import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.lifecycle.SubmissionStatus;
import com.archforge.graphcompiler.compiler.DagCompiler;
import com.archforge.graphcompiler.compiler.GraphCompiler;
import com.archforge.graphcompiler.exception.CompilationException;
import com.archforge.graphcompiler.exception.DAGCycleException;
import com.archforge.graphcompiler.exception.ValidationException;
import com.archforge.graphcompiler.validation.TopologyValidator;
import com.archforge.graphcompiler.validation.ValidationError;
import com.archforge.graphcompiler.validation.ValidationResult;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Synchronous compile endpoint.
 *
 * <p>This is a <em>dry run</em>: it validates and compiles, and returns the DAG, without
 * publishing anything to Kafka. The asynchronous path — publish to
 * {@code design.submissions} and watch the pipeline progress — is what a user actually
 * wants, and it is served by the design service.
 *
 * <p>Three correctness points about the status codes, each of which was wrong before:
 * <ul>
 *   <li><b>Validation rejection is 400</b>, not 500. The user's topology is wrong; a
 *       5xx tells them to retry a request that can never succeed. Every message is
 *       returned with its field path so the UI can point at the offending node.</li>
 *   <li><b>A cycle is 400</b>, and is distinguished from an internal failure by
 *       {@link DAGCycleException}, which extends {@code CompilationException.UncompilableGraph}.
 *       Both used to be a {@code CompilationException}, so every cycle surfaced as an
 *       internal error.</li>
 *   <li><b>Malformed JSON is 400</b>. {@code HttpMessageNotReadableException} is raised
 *       during argument resolution, before the method body runs, so the in-method catch
 *       cannot see it — it is handled by {@code GlobalExceptionHandler}.</li>
 * </ul>
 *
 * <p>The response's {@code simulationId} is explicitly {@code null}: this path does not
 * create a simulation, and returning a random UUID here previously invited callers to
 * poll a submission that did not exist.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/compile")
public class CompilationController {

    private final TopologyValidator topologyValidator;
    private final GraphCompiler graphCompiler;
    private final DagCompiler dagCompiler;
    private final MeterRegistry meterRegistry;

    public CompilationController(TopologyValidator topologyValidator,
                                 GraphCompiler graphCompiler,
                                 DagCompiler dagCompiler,
                                 MeterRegistry meterRegistry) {
        this.topologyValidator = topologyValidator;
        this.graphCompiler = graphCompiler;
        this.dagCompiler = dagCompiler;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CompilationResponse> compileTopology(@RequestBody(required = false) Topology topology) {
        long startTime = System.nanoTime();

        if (topology == null) {
            return ResponseEntity.badRequest()
                    .body(CompilationResponse.failure(List.of("Request body is required"), List.of()));
        }

        try {
            ValidationResult validation = topologyValidator.validate(topology);
            CompiledGraph graph = dagCompiler.compile(graphCompiler.compile(topology));

            List<Finding> warnings = findingsAt(validation, ValidationError.Severity.WARNING);
            List<Finding> notes = findingsAt(validation, ValidationError.Severity.INFO);

            recordTiming(startTime, true);

            return ResponseEntity.ok(CompilationResponse.success(
                    graph, elapsedMs(startTime), warnings, notes));

        } catch (ValidationException e) {
            // Use the structured findings, not getErrors(): flattening them to bare
            // strings is what made every rejection report the same COMPILATION_FAILED
            // code with no field, leaving the client unable to point at the bad node.
            recordTiming(startTime, false);
            return ResponseEntity.badRequest().body(CompilationResponse.rejected(
                    e.blockingFindings().stream()
                            .map(finding -> new Finding(finding.field(), finding.code(), finding.message()))
                            .toList()));

        } catch (DAGCycleException e) {
            // Client error: the graph cannot be made acyclic by retrying.
            recordTiming(startTime, false);
            log.warn("Rejected a cyclic topology: {}", e.getCyclePath());
            return ResponseEntity.badRequest().body(CompilationResponse.failure(
                    List.of(e.getMessage()), List.of()));

        } catch (CompilationException.UncompilableGraph e) {
            recordTiming(startTime, false);
            return ResponseEntity.badRequest().body(CompilationResponse.failure(
                    List.of(e.getMessage()), List.of()));

        } catch (Exception e) {
            recordTiming(startTime, false);
            log.error("Compilation failed unexpectedly", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(CompilationResponse.failure(
                            List.of("Compilation failed. Please try again later."), List.of()));
        }
    }

    /**
     * Tells a caller what this endpoint does and does not do.
     *
     * <p>Present because the dry-run endpoint and the async pipeline look identical from
     * the frontend's side, and a client that posts here and then polls for results waits
     * forever for a simulation that was never created.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> describe() {
        return ResponseEntity.ok(Map.of(
                "synchronous", true,
                "publishesToKafka", false,
                "note", "This is a dry run: it returns a compiled graph and creates no simulation. "
                        + "To run a simulation, submit to design.submissions via the design service "
                        + "and poll /api/v1/submissions/{submissionId}.",
                "asyncSubmissionEndpoint", "/api/v1/submissions",
                "nodeTypes", com.archforge.contracts.canvas.NodeType.getAllValues(),
                "maxNodes", 500,
                "maxEdges", 2000,
                "validationStatuses", SubmissionStatus.allValues()));
    }

    private List<Finding> findingsAt(ValidationResult result, ValidationError.Severity severity) {
        return result.getFindings().stream()
                .filter(finding -> finding.getSeverity() == severity)
                .map(finding -> new Finding(finding.getField(), finding.getCode(), finding.getMessage()))
                .toList();
    }

    private void recordTiming(long startTime, boolean success) {
        long millis = elapsedMs(startTime);
        meterRegistry.timer("graph.compilation.duration").record(millis, TimeUnit.MILLISECONDS);
        meterRegistry.counter(success
                ? "graph.compilation.sync.success"
                : "graph.compilation.sync.failure").increment();
    }

    private long elapsedMs(long startTime) {
        return Math.max(0, (System.nanoTime() - startTime) / 1_000_000);
    }

    /**
     * A validation finding, attributed to a field path.
     *
     * @param field dotted/indexed path, e.g. {@code nodes[2].type}
     * @param code  stable machine-readable code
     * @param message human-readable explanation
     */
    public record Finding(String field, String code, String message) {
    }

    /**
     * Compile result.
     *
     * @param simulationId always null: a dry run creates no simulation, and returning a
     *                      random UUID previously implied one existed
     * @param errors blocking findings; non-empty means the request was rejected
     * @param warnings findings that do not block compilation
     */
    public record CompilationResponse(
            String simulationId,
            CompiledGraph compiledGraph,
            long durationMs,
            List<Finding> errors,
            List<Finding> warnings,
            List<Finding> notes) {

        public static CompilationResponse success(CompiledGraph graph,
                                                   long durationMs,
                                                   List<Finding> warnings,
                                                   List<Finding> notes) {
            return new CompilationResponse(null, graph, durationMs, List.of(), warnings, notes);
        }

        /** Rejection carrying per-field attribution from the validators. */
        public static CompilationResponse rejected(List<Finding> errors) {
            return new CompilationResponse(null, null, 0, errors, List.of(), List.of());
        }

        /** Rejection from a plain message with no field attribution. */
        public static CompilationResponse failure(List<String> errors, List<Finding> warnings) {
            List<Finding> findings = errors.stream()
                    .map(message -> new Finding(null, "COMPILATION_FAILED", message))
                    .toList();
            return new CompilationResponse(null, null, 0, findings, warnings, List.of());
        }
    }
}
