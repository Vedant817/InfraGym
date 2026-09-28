package com.archforge.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
public class FallbackController {

    @GetMapping("/fallback/graph-compiler")
    public Mono<ResponseEntity<Map<String, String>>> graphCompilerFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Graph Compiler service is temporarily unavailable")));
    }

    @GetMapping("/fallback/canvas-session")
    public Mono<ResponseEntity<Map<String, String>>> canvasSessionFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Canvas Session service is temporarily unavailable")));
    }

    @GetMapping("/fallback/ai-evaluator")
    public Mono<ResponseEntity<Map<String, String>>> aiEvaluatorFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "AI Evaluator service is temporarily unavailable")));
    }
}
