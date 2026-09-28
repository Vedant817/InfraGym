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

    private String simulationId;
    private String submissionId;
    private String userId;
    private String sessionId;
    private CompiledGraph compiledGraph;
    private Instant createdAt;

    public static SimulationTaskEvent from(String submissionId, String userId, String sessionId, CompiledGraph compiledGraph) {
        return SimulationTaskEvent.builder()
                .simulationId(UUID.randomUUID().toString())
                .submissionId(submissionId)
                .userId(userId)
                .sessionId(sessionId)
                .compiledGraph(compiledGraph)
                .createdAt(Instant.now())
                .build();
    }
}
