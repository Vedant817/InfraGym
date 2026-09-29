package com.archforge.contracts.canvas;

import java.util.Map;

public record Node(
    String id,
    String type, // e.g., "LoadBalancer", "PostgreSQL", "Kafka"
    Map<String, Object> config
) {}