package com.archforge.graphcompiler.model;

import lombok.Builder;
import lombok.Value;

import java.util.Objects;

@Value
@Builder
public class CompiledEdge {
    String id;
    String source;
    String target;
    String label;
    String protocol;
    Double bandwidthMbps;
    Double latencyMs;
    String direction;

    public enum Direction {
        UNIDIRECTIONAL,
        BIDIRECTIONAL
    }

    public static class CompiledEdgeBuilder {
        public CompiledEdge build() {
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(target, "target must not be null");

            return new CompiledEdge(id, source, target, label, protocol, bandwidthMbps, latencyMs, direction);
        }
    }
}
