package com.archforge.canvas.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

@Value
@Builder
public class CanvasState {
    String sessionId;
    String topologyJson;
    List<String> connectedUsers;
    Instant lastUpdated;
}
