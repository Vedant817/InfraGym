package com.archforge.graphcompiler.schema;

import com.archforge.graphcompiler.model.CompiledGraph;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SimulationTaskEvent {

    private String eventId;
    private String simulationId;
    private String submissionId;
    private String userId;
    private String sessionId;
    private String version;
    private String source;
    private String correlationId;
    private String scenarioType;
    private CompiledGraph compiledGraph;
    private Instant createdAt;

    public static SimulationTaskEvent from(String submissionId, String userId, String sessionId, CompiledGraph compiledGraph) {
        return SimulationTaskEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .simulationId(UUID.randomUUID().toString())
                .submissionId(submissionId)
                .userId(userId)
                .sessionId(sessionId)
                .version("1.0")
                .source("graph-compiler")
                .correlationId(UUID.randomUUID().toString())
                .compiledGraph(compiledGraph)
                .createdAt(Instant.now())
                .build();
    }
}
