package com.archforge.graphcompiler.event;

import com.archforge.graphcompiler.compiler.DagCompiler;
import com.archforge.graphcompiler.compiler.GraphCompiler;
import com.archforge.graphcompiler.compiler.InternalGraph;
import com.archforge.graphcompiler.model.CompiledGraph;
import com.archforge.graphcompiler.schema.DesignSubmissionEvent;
import com.archforge.graphcompiler.schema.SimulationTaskEvent;
import com.archforge.graphcompiler.validation.TopologyValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompilationService {

    private final TopologyValidator topologyValidator;
    private final GraphCompiler graphCompiler;
    private final DagCompiler dagCompiler;
    private final SimulationTaskProducer taskProducer;

    public void compileAndPublish(DesignSubmissionEvent event) {
        log.info("Starting compilation for submission: {}", event.getSubmissionId());

        topologyValidator.validate(event.getTopology());

        InternalGraph internalGraph = graphCompiler.compile(event.getTopology());

        CompiledGraph compiledGraph = dagCompiler.compile(internalGraph);

        SimulationTaskEvent taskEvent = SimulationTaskEvent.from(
                event.getSubmissionId(),
                event.getUserId(),
                event.getSessionId(),
                compiledGraph
        );

        taskProducer.publishSimulationTask(taskEvent);

        log.info("Compilation completed for submission: {}, simulation: {}",
                event.getSubmissionId(), taskEvent.getSimulationId());
    }
}
