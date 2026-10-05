package com.archforge.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
public class FallbackController {

    // Gateway forwards preserve POST/PUT/DELETE as well as GET.
    @RequestMapping("/fallback/graph-compiler")
    public Mono<ResponseEntity<Map<String, String>>> graphCompilerFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Graph Compiler service is temporarily unavailable")));
    }

    @RequestMapping("/fallback/canvas-session")
    public Mono<ResponseEntity<Map<String, String>>> canvasSessionFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Canvas Session service is temporarily unavailable")));
    }

    @RequestMapping("/fallback/ai-evaluator")
    public Mono<ResponseEntity<Map<String, String>>> aiEvaluatorFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "AI Evaluator service is temporarily unavailable")));
    }

    @RequestMapping("/fallback/design-service")
    public Mono<ResponseEntity<Map<String, String>>> designServiceFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Design service is temporarily unavailable")));
    }
}
