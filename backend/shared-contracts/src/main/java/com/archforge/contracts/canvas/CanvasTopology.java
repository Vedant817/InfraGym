package com.archforge.contracts.canvas;

import java.util.List;
import java.util.Map;

public record CanvasTopology(
    String submissionId,
    String userId,
    List<Node> nodes,
    List<Edge> edges,
    Map<String, Object> globalConstraints
) {}