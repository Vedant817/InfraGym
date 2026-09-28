package com.archforge.graphcompiler.schema;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum NodeType {
    SERVICE("service"),
    CACHE("cache"),
    DATABASE("database"),
    QUEUE("queue"),
    LOAD_BALANCER("load_balancer"),
    CDN("cdn"),
    WORKER("worker"),
    EXTERNAL("external");

    private final String value;

    NodeType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static NodeType fromValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown node type: " + value));
    }

    public static Set<String> getAllValues() {
        return Arrays.stream(values())
                .map(NodeType::getValue)
                .collect(Collectors.toSet());
    }

    public boolean isStorage() {
        return this == DATABASE || this == CACHE;
    }

    public boolean isCompute() {
        return this == SERVICE || this == WORKER;
    }

    public boolean isMessaging() {
        return this == QUEUE;
    }

    public boolean isNetwork() {
        return this == LOAD_BALANCER || this == CDN;
    }
}
