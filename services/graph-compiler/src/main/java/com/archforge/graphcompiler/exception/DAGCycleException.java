package com.archforge.graphcompiler.exception;

import lombok.Getter;

import java.util.List;

/**
 * Thrown when a topology contains a cycle and therefore cannot be compiled to a DAG.
 *
 * <p>Distinct from {@link CompilationException} so the HTTP layer can distinguish
 * "your graph has a cycle" — a client error, 400 — from "the compiler broke" — a server
 * error, 500. Previously both were {@code CompilationException}, so every cycle was
 * reported to the user as an internal server failure and suggested they retry a
 * request that could never succeed.
 *
 * <p>This type existed but was never thrown: {@code TopologicalSorter} threw a plain
 * {@code CompilationException}, and {@code TopologyValidator} imported this class
 * without using it. Cycle detection therefore produced a 500.
 */
@Getter
public class DAGCycleException extends CompilationException.UncompilableGraph {

    private final List<String> cyclePath;

    public DAGCycleException(List<String> cyclePath) {
        super("Cycle detected in topology: " + String.join(" -> ", cyclePath)
                + ". A directed acyclic graph cannot contain a circular dependency.");
        this.cyclePath = List.copyOf(cyclePath);
    }

    public DAGCycleException(String message, List<String> cyclePath) {
        super(message);
        this.cyclePath = List.copyOf(cyclePath);
    }
}
