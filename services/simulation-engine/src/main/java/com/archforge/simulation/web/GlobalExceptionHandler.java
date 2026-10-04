package com.archforge.simulation.web;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;

/**
 * Uniform error responses for the read-only simulation API.
 *
 * <p>Same shape and same status-code discipline as the compiler's handler, so a client
 * has one error path across the platform rather than one per service. A client mistake
 * is 4xx; an internal failure is 5xx.
 *
 * <p>No internal detail leaks. Exception messages are frequently paths, SQL fragments
 * or hostnames, so the client gets a stable message and the detail goes to the log.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final MeterRegistry meterRegistry;

    public GlobalExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        log.debug("Rejected malformed request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "INVALID_REQUEST",
                "A request parameter was missing or invalid.",
                "Check the path variables and query parameters; see /swagger-ui.html for the schema."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ErrorResponse.of(
                "UNSUPPORTED_MEDIA_TYPE",
                "This endpoint accepts application/json.",
                "Set the Content-Type header to application/json."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(
                "NOT_FOUND", "No such endpoint: " + ex.getResourcePath(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        meterRegistry.counter("simulation.api.unhandled_error").increment();
        log.error("Unhandled exception while serving a request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.of(
                "INTERNAL_ERROR",
                "An unexpected error occurred while processing the request.",
                "If this persists, check the simulation engine's logs for the request time."));
    }

    /**
     * @param code      stable machine-readable code for client branching
     * @param message   human-readable summary
     * @param hint      what the caller should do next
     * @param timestamp when the error was produced
     */
    public record ErrorResponse(String code, String message, String hint, String timestamp) {

        public static ErrorResponse of(String code, String message, String hint) {
            return new ErrorResponse(code, message, hint, Instant.now().toString());
        }
    }
}
