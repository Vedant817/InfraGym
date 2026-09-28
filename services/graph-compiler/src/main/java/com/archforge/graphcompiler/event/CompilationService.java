package com.archforge.graphcompiler.event;

import com.archforge.graphcompiler.compiler.DagCompiler;
import com.archforge.graphcompiler.compiler.GraphCompiler;
import com.archforge.graphcompiler.compiler.InternalGraph;
import com.archforge.graphcompiler.model.CompiledGraph;
import com.archforge.graphcompiler.schema.DesignSubmissionEvent;
import com.archforge.graphcompiler.schema.SimulationTaskEvent;
import com.archforge.graphcompiler.validation.TopologyValidator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompilationService {

    private final TopologyValidator topologyValidator;
    private final GraphCompiler graphCompiler;
    private final DagCompiler dagCompiler;
    private final SimulationTaskProducer taskProducer;
    private final MeterRegistry meterRegistry;

    public void compileAndPublish(DesignSubmissionEvent event) {
        log.info("Starting compilation for submission: {}", event.getSubmissionId());

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            topologyValidator.validate(event.getTopology());

            InternalGraph internalGraph = graphCompiler.compile(event.getTopology());

            CompiledGraph compiledGraph = dagCompiler.compile(internalGraph);

            SimulationTaskEvent taskEvent = SimulationTaskEvent.from(
                    event.getSubmissionId(),
                    event.getUserId(),
                    event.getSessionId(),
                    compiledGraph
            );

            taskProducer.publishSimulationTask(taskEvent)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish simulation task for submission: {}", event.getSubmissionId(), ex);
                            meterRegistry.counter("compilation.publish.failed").increment();
                        } else {
                            meterRegistry.counter("compilation.publish.success").increment();
                        }
                    });

            sample.stop(meterRegistry.timer("compilation.duration"));

            log.info("Compilation completed for submission: {}, simulation: {}",
                    event.getSubmissionId(), taskEvent.getSimulationId());
        } catch (Exception e) {
            meterRegistry.counter("compilation.failed").increment();
            throw e;
        }
    }
}
