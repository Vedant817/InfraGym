package com.archforge.graphcompiler.schema;

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
public class DesignSubmissionEvent {

    private String submissionId;
    private String userId;
    private String sessionId;
    private ReactFlowTopology topology;
    private Instant submittedAt;

    public static DesignSubmissionEvent create(String userId, String sessionId, ReactFlowTopology topology) {
        return DesignSubmissionEvent.builder()
                .submissionId(UUID.randomUUID().toString())
                .userId(userId)
                .sessionId(sessionId)
                .topology(topology)
                .submittedAt(Instant.now())
                .build();
    }
}
