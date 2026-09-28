package com.archforge.graphcompiler.exception;

import lombok.Getter;

import java.util.List;

@Getter
public class DAGCycleException extends RuntimeException {

    private final List<String> cyclePath;

    public DAGCycleException(List<String> cyclePath) {
        super("Cycle detected in topology: " + String.join(" -> ", cyclePath));
        this.cyclePath = cyclePath;
    }
}
