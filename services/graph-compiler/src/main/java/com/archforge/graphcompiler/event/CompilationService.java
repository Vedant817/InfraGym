package com.archforge.graphcompiler.event;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.event.DesignSubmittedEvent;
import com.archforge.contracts.event.SimulationRequestedEvent;
import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.lifecycle.ScenarioType;
import com.archforge.graphcompiler.compiler.DagCompiler;
import com.archforge.graphcompiler.compiler.GraphCompiler;
import com.archforge.graphcompiler.compiler.InternalGraph;
import com.archforge.graphcompiler.exception.ValidationException;
import com.archforge.graphcompiler.validation.TopologyValidator;
import com.archforge.graphcompiler.validation.ValidationResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Turns a submitted design into a runnable simulation task.
 *
 * <p>The scenario chosen by the user is carried end to end. Previously the
 * compiler published a task with {@code scenarioType} left null, the engine
 * quietly substituted {@code NORMAL}, and all eight failure scenarios plus the
 * entire workload-shape library were unreachable dead code — a bug invisible
 * because nothing asserted on it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompilationService {

    private final TopologyValidator topologyValidator;
    private final GraphCompiler graphCompiler;
    private final DagCompiler dagCompiler;
    private final SimulationTaskProducer taskProducer;
    private final MeterRegistry meterRegistry;

    @Value("${graph-compiler.simulation.default-duration-seconds:60}")
    private long defaultDurationSeconds;

    @Value("${graph-compiler.simulation.tick-millis:1000}")
    private long tickMillis;

    /**
     * Compile and publish. Blocking on the publish future is deliberate: the
     * caller (a Kafka listener with manual acknowledgement) must not commit the
     * submission offset until the task is durably on {@code simulation.tasks},
     * otherwise a broker blip between compile and send loses the work silently.
     */
    public SimulationRequestedEvent compileAndPublish(DesignSubmittedEvent event) {
        log.info("Compiling submission {} (scenario={}, correlation={})",
                event.submissionId(), event.scenario(), event.correlationId());

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            ValidationResult validationResult = topologyValidator.validate(event.topology());

            InternalGraph internalGraph = graphCompiler.compile(event.topology());
            CompiledGraph compiled = dagCompiler.compile(internalGraph);

            List<String> warnings = validationResult.getErrors().stream()
                    .filter(e -> e.getSeverity() == com.archforge.graphcompiler.validation.ValidationError.Severity.WARNING)
                    .map(com.archforge.graphcompiler.validation.ValidationError::getMessage)
                    .toList();

            CompiledGraph graphWithWarnings = warnings.isEmpty()
                    ? compiled
                    : rebuildWithWarnings(compiled, warnings);

            ScenarioType scenario = event.scenarioType();
            SimulationRequestedEvent task = SimulationRequestedEvent.from(
                    event, graphWithWarnings, scenario,
                    defaultDurationSeconds, tickMillis, seedFor(event));

            taskProducer.publishSimulationTask(task)
                    .get(30, TimeUnit.SECONDS);

            sample.stop(meterRegistry.timer("graph.compilation.duration"));
            meterRegistry.counter("graph.compilation.success").increment();

            log.info("Compiled submission {} -> simulation {} ({} nodes, {} edges, {} warning(s))",
                    event.submissionId(), task.simulationId(),
                    graphWithWarnings.nodeCount(), graphWithWarnings.edgeCount(), warnings.size());

            return task;

        } catch (ValidationException e) {
            sample.stop(meterRegistry.timer("graph.compilation.duration"));
            meterRegistry.counter("graph.compilation.rejected").increment();
            log.warn("Submission {} rejected by validation: {}", event.submissionId(), e.getErrors());
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sample.stop(meterRegistry.timer("graph.compilation.duration"));
            meterRegistry.counter("graph.compilation.failed").increment();
            throw new IllegalStateException("Interrupted while publishing simulation task", e);
        } catch (Exception e) {
            sample.stop(meterRegistry.timer("graph.compilation.duration"));
            meterRegistry.counter("graph.compilation.failed").increment();
            log.error("Compilation failed for submission {}", event.submissionId(), e);
            throw new IllegalStateException("Failed to compile submission " + event.submissionId(), e);
        }
    }

    /**
     * Deterministic seed derived from the submission id, so re-running the same
     * design reproduces the same workload sampling, jitter and failure-site
     * choice. Without this the platform reports a different P99 on every attempt
     * and the AI's advice cannot be checked against the numbers.
     */
    private long seedFor(DesignSubmittedEvent event) {
        return Integer.toUnsignedLong(Objects.hashCode(event.submissionId()));
    }

    private CompiledGraph rebuildWithWarnings(CompiledGraph graph, List<String> warnings) {
        return CompiledGraph.builder()
                .nodes(graph.getNodes())
                .edges(graph.getEdges())
                .graphMetadata(graph.getGraphMetadata())
                .name(graph.getName())
                .description(graph.getDescription())
                .warnings(warnings)
                .compiledAt(graph.getCompiledAt())
                .build();
    }
}
