package com.archforge.graphcompiler.controller;

import com.archforge.graphcompiler.compiler.DagCompiler;
import com.archforge.graphcompiler.compiler.GraphCompiler;
import com.archforge.graphcompiler.compiler.InternalGraph;
import com.archforge.graphcompiler.exception.CompilationException;
import com.archforge.graphcompiler.exception.ValidationException;
import com.archforge.graphcompiler.model.CompiledGraph;
import com.archforge.graphcompiler.schema.ReactFlowTopology;
import com.archforge.graphcompiler.validation.TopologyValidator;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/v1/compile")
@RequiredArgsConstructor
public class CompilationController {

    private final TopologyValidator topologyValidator;
    private final GraphCompiler graphCompiler;
    private final DagCompiler dagCompiler;
    private final MeterRegistry meterRegistry;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CompilationResponse> compileTopology(@Valid @RequestBody(required = false) ReactFlowTopology topology) {
        log.info("Received manual compilation request");

        long startTime = System.currentTimeMillis();

        try {
            if (topology == null) {
                return ResponseEntity.badRequest().body(CompilationResponse.error("Request body is required"));
            }

            topologyValidator.validate(topology);

            InternalGraph internalGraph = graphCompiler.compile(topology);
            CompiledGraph compiledGraph = dagCompiler.compile(internalGraph);

            long duration = System.currentTimeMillis() - startTime;
            meterRegistry.timer("manual.compilation.duration").record(duration, TimeUnit.MILLISECONDS);
            meterRegistry.counter("manual.compilation.success").increment();

            log.info("Manual compilation completed in {} ms", duration);

            return ResponseEntity.ok(CompilationResponse.success(
                    UUID.randomUUID().toString(),
                    compiledGraph,
                    duration
            ));
        } catch (ValidationException e) {
            long duration = System.currentTimeMillis() - startTime;
            meterRegistry.timer("manual.compilation.duration").record(duration, TimeUnit.MILLISECONDS);
            meterRegistry.counter("manual.compilation.failed").increment();

            log.warn("Manual compilation validation failed: {}", e.getMessage());

            return ResponseEntity.badRequest().body(CompilationResponse.error(e.getMessage()));
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            meterRegistry.timer("manual.compilation.duration").record(duration, TimeUnit.MILLISECONDS);
            meterRegistry.counter("manual.compilation.failed").increment();

            log.error("Manual compilation failed", e);

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(CompilationResponse.error("Compilation failed. Please try again later."));
        }
    }

    @lombok.Value
    @lombok.Builder
    public static class CompilationResponse {
        String simulationId;
        CompiledGraph compiledGraph;
        long durationMs;
        String error;

        public static CompilationResponse success(String simulationId, CompiledGraph compiledGraph, long durationMs) {
            return CompilationResponse.builder()
                    .simulationId(simulationId)
                    .compiledGraph(compiledGraph)
                    .durationMs(durationMs)
                    .build();
        }

        public static CompilationResponse error(String error) {
            return CompilationResponse.builder()
                    .error(error)
                    .build();
        }
    }
}
