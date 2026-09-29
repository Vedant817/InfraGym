package com.archforge.contracts.canvas;

public record Edge(
    String id,
    String source,
    String target,
    String protocol // e.g., "HTTP", "gRPC", "TCP"
) {}