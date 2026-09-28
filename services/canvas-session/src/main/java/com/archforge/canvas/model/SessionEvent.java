package com.archforge.canvas.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class SessionEvent {
    String sessionId;
    String eventType;
    String userId;
    String payload;
    Instant timestamp;
}
