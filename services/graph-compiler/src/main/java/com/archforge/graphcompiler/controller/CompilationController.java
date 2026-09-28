package com.archforge.graphcompiler.controller;

import com.archforge.graphcompiler.compiler.DagCompiler;
import com.archforge.graphcompiler.compiler.GraphCompiler;
import com.archforge.graphcompiler.compiler.InternalGraph;
import com.archforge.graphcompiler.model.CompiledGraph;
import com.archforge.graphcompiler.schema.DesignSubmissionEvent;
import com.archforge.graphcompiler.schema.ReactFlowTopology;
import com.archforge.graphcompiler.validation.TopologyValidator;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/compile")
@RequiredArgsConstructor
public class CompilationController {

    private final TopologyValidator topologyValidator;
    private final GraphCompiler graphCompiler;
    private final DagCompiler dagCompiler;
    private final MeterRegistry meterRegistry;

    @PostMapping
    public ResponseEntity<CompilationResponse> compileTopology(@RequestBody ReactFlowTopology topology) {
        log.info("Received manual compilation request");

        long startTime = System.currentTimeMillis();

        try {
            topologyValidator.validate(topology);

            InternalGraph internalGraph = graphCompiler.compile(topology);
            CompiledGraph compiledGraph = dagCompiler.compile(internalGraph);

            long duration = System.currentTimeMillis() - startTime;
            meterRegistry.timer("manual.compilation.duration").record(duration, java.util.concurrent.TimeUnit.MILLISECONDS);
            meterRegistry.counter("manual.compilation.success").increment();

            log.info("Manual compilation completed in {} ms", duration);

            return ResponseEntity.ok(CompilationResponse.success(
                    UUID.randomUUID().toString(),
                    compiledGraph,
                    duration
            ));
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            meterRegistry.timer("manual.compilation.duration").record(duration, java.util.concurrent.TimeUnit.MILLISECONDS);
            meterRegistry.counter("manual.compilation.failed").increment();

            log.error("Manual compilation failed", e);

            return ResponseEntity.badRequest().body(CompilationResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
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
