package com.archforge.contracts.canvas;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every component kind the canvas can place.
 *
 * <p>The wire value is the string persisted in MongoDB, sent over Kafka and
 * rendered by the frontend, so it is {@code snake_case} and stable. Adding a
 * constant is a backwards-compatible change; renaming or removing one is not.
 *
 * <p>Simulation defaults live in {@code SimulationParameterRegistry} on the
 * compiler, keyed by this enum. This type deliberately carries no numeric
 * defaults so that there is exactly one place per concern: capabilities here,
 * physical characteristics in the registry.
 */
public enum NodeType {

    CLIENT("client", NodeCategory.EDGE, "Origin"),

    CDN("cdn", NodeCategory.EDGE, "Edge cache"),

    LOAD_BALANCER("load_balancer", NodeCategory.NETWORK, "Load balancer"),
    GATEWAY("gateway", NodeCategory.NETWORK, "API gateway"),

    AUTH("auth", NodeCategory.SECURITY, "Identity provider"),

    SERVICE("service", NodeCategory.COMPUTE, "Service"),
    WORKER("worker", NodeCategory.COMPUTE, "Worker"),
    FUNCTION("function", NodeCategory.COMPUTE, "Function"),

    QUEUE("queue", NodeCategory.MESSAGING, "Message queue"),

    DATABASE("database", NodeCategory.STORAGE, "Database"),
    CACHE("cache", NodeCategory.STORAGE, "Cache"),
    STORAGE("storage", NodeCategory.STORAGE, "Object storage"),

    MONITORING("monitoring", NodeCategory.OBSERVABILITY, "Monitoring"),

    EXTERNAL("external", NodeCategory.EXTERNAL, "External service");

    private final String value;
    private final NodeCategory category;
    private final String displayName;

    NodeType(String value, NodeCategory category, String displayName) {
        this.value = value;
        this.category = category;
        this.displayName = displayName;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public NodeCategory getCategory() {
        return category;
    }

    /** Human-readable label used by the frontend palette and the AI evaluator. */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Accepts both the wire value ({@code load_balancer}) and the enum constant
     * name ({@code LOAD_BALANCER}) so hand-written payloads and older stored
     * documents keep parsing.
     *
     * @return the matching type
     * @throws IllegalArgumentException if the value matches nothing; the message
     *         lists the accepted values so API clients can self-correct
     */
    @JsonCreator
    public static NodeType fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Node type is required. Valid types: " + String.join(", ", getAllValues()));
        }
        String needle = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(values())
                .filter(type -> type.value.equals(needle)
                        || type.name().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown node type '" + value + "'. Valid types: " + String.join(", ", getAllValues())));
    }

    /** Like {@link #fromValue(String)} but returns {@code null} instead of throwing. */
    public static NodeType findByValue(String value) {
        try {
            return fromValue(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Wire values in declaration order, which is also the palette order. */
    public static Set<String> getAllValues() {
        return Arrays.stream(values())
                .map(NodeType::getValue)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public static Map<String, NodeType> byWireValue() {
        Map<String, NodeType> map = new LinkedHashMap<>();
        for (NodeType type : values()) {
            map.put(type.value, type);
        }
        return map;
    }

    // --- capability helpers, one shared vocabulary ----------------------------

    public boolean isCompute() {
        return category == NodeCategory.COMPUTE;
    }

    public boolean isStorage() {
        return category == NodeCategory.STORAGE;
    }

    public boolean isMessaging() {
        return category == NodeCategory.MESSAGING;
    }

    public boolean isNetwork() {
        return category == NodeCategory.NETWORK;
    }

    public boolean isSecurity() {
        return category == NodeCategory.SECURITY;
    }

    public boolean isObservability() {
        return category == NodeCategory.OBSERVABILITY;
    }

    public boolean isExternal() {
        return category == NodeCategory.EXTERNAL;
    }

    /**
     * Whether this node may sit at the head of a request path, i.e. accept
     * traffic with no upstream dependency.
     */
    public boolean canBeEntryPoint() {
        return isNetwork() || this == CLIENT || this == CDN;
    }

    /**
     * Whether this node terminates a request path. Sinks are expected to be
     * leaves; a design where a database has downstream consumers is unusual but
     * legal (CDC), so this drives warnings rather than errors.
     */
    public boolean canBeTerminal() {
        return isStorage() || this == EXTERNAL || this == MONITORING;
    }

    /**
     * Whether traffic can be buffered at this node and therefore absorb bursts.
     */
    public boolean buffers() {
        return isMessaging() || this == CACHE;
    }

    /**
     * Whether failure of this node is visible to the caller as request failure
     * rather than being silently absorbed.
     */
    public boolean failureIsFailStop() {
        return !isObservability();
    }
}
