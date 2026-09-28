package com.archforge.graphcompiler.controller;

import com.archforge.graphcompiler.exception.CompilationException;
import com.archforge.graphcompiler.exception.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.*;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(ValidationException e) {
        log.warn("Validation failed: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "errors", e.getErrors()
        ));
    }

    @ExceptionHandler(CompilationException.class)
    public ResponseEntity<Map<String, Object>> handleCompilationException(CompilationException e) {
        log.error("Compilation failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Compilation failed. Please try again later."
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "An unexpected error occurred. Please try again later."
        ));
    }
}
