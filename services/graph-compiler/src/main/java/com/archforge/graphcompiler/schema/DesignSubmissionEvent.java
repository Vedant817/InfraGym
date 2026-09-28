package com.archforge.graphcompiler.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesignSubmissionEvent {

    private String eventId;
    private String submissionId;
    private String userId;
    private String sessionId;
    private String version;
    private String source;
    private String correlationId;
    private ReactFlowTopology topology;
    private Instant submittedAt;

    public static DesignSubmissionEvent create(String userId, String sessionId, ReactFlowTopology topology) {
        return DesignSubmissionEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .submissionId(UUID.randomUUID().toString())
                .userId(userId)
                .sessionId(sessionId)
                .version("1.0")
                .source("api-gateway")
                .correlationId(UUID.randomUUID().toString())
                .topology(topology)
                .submittedAt(Instant.now())
                .build();
    }
}
