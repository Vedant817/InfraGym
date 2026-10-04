package com.archforge.graphcompiler.controller;

import com.archforge.graphcompiler.exception.CompilationException;
import com.archforge.graphcompiler.exception.DAGCycleException;
import com.archforge.graphcompiler.exception.ValidationException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Uniform error responses.
 *
 * <p>Every error the API returns has the same shape, so a client has one code path
 * rather than one per failure mode. Each handler also maps to the correct status:
 * a client mistake is 4xx and an internal failure is 5xx.
 *
 * <p><b>Malformed JSON is the case that matters.</b> {@code HttpMessageNotReadableException}
 * is thrown during argument resolution — before the controller method body executes — so
 * an in-method {@code catch} can never see it. Without a handler here it surfaces as a
 * 500, telling the user their request failed server-side when in fact their JSON was
 * malformed. It is a 400.
 *
 * <p><b>No internal detail leaks.</b> Exception messages are frequently paths, SQL
 * fragments or hostnames. Clients get a stable, actionable message; the full detail goes
 * to the log with the correlation id.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final MeterRegistry meterRegistry;

    public GlobalExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ValidationException ex) {
        List<CompilationController.Finding> findings = ex.blockingFindings().stream()
                .map(finding -> new CompilationController.Finding(
                        finding.field(), finding.code(), finding.message()))
                .toList();

        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "VALIDATION_FAILED",
                "The design contains problems that prevent compilation.",
                findings,
                "Fix the highlighted fields and submit again."));
    }

    @ExceptionHandler(DAGCycleException.class)
    public ResponseEntity<ErrorResponse> handleCycle(DAGCycleException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "CYCLE_DETECTED",
                ex.getMessage(),
                List.of(),
                "A directed acyclic graph cannot contain a circular dependency. "
                        + "Remove one of the connections in the cycle."));
    }

    @ExceptionHandler(CompilationException.UncompilableGraph.class)
    public ResponseEntity<ErrorResponse> handleUncompilable(CompilationException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "UNCOMPILABLE_GRAPH", ex.getMessage(), List.of(), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        // 400, not 500: this is the client's payload, and it fails before the handler runs.
        log.debug("Rejected malformed request body: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "MALFORMED_REQUEST",
                "The request body could not be parsed as JSON.",
                List.of(),
                "Expected {\"nodes\": [...], \"edges\": [...]} with node objects containing "
                        + "id, type, position and data."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ErrorResponse.of(
                "UNSUPPORTED_MEDIA_TYPE",
                "This endpoint accepts application/json.",
                List.of(),
                "Set the Content-Type header to application/json."));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "INVALID_REQUEST", "A request parameter was missing or invalid.", List.of(), null));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(
                "NOT_FOUND", "No such endpoint: " + ex.getResourcePath(), List.of(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        meterRegistry.counter("graph.compilation.unhandled_error").increment();
        log.error("Unhandled exception while serving a request", ex);
        // Deliberately generic: an exception message can contain a filesystem path, a
        // SQL fragment or an internal hostname.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.of(
                "INTERNAL_ERROR",
                "An unexpected error occurred while processing the request.",
                List.of(),
                "If this persists, contact support with the time of the request."));
    }

    /**
     * The single error shape every endpoint returns.
     *
     * @param code    stable machine-readable code for client branching
     * @param message human-readable summary
     * @param details per-field findings, empty when not applicable
     * @param hint    what the user should do next
     */
    public record ErrorResponse(
            String code,
            String message,
            List<CompilationController.Finding> details,
            String hint,
            String timestamp) {

        public static ErrorResponse of(String code, String message,
                                       List<CompilationController.Finding> details,
                                       String hint) {
            return new ErrorResponse(code, message,
                    details == null ? List.of() : details, hint, Instant.now().toString());
        }
    }
}
