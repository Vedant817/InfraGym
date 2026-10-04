package com.archforge.contracts.canvas;

/**
 * Coarse functional grouping of a {@link NodeType}.
 *
 * <p>The compiler, the simulator and the evaluator all need to reason about a
 * node's <em>role</em> rather than its concrete type ("is this something that
 * can saturate?" vs "is this a hard dependency?"). Categories give one shared
 * vocabulary so those rules are written once.
 */
public enum NodeCategory {

    /** Callers that originate traffic but are not part of the system under test. */
    EDGE,

    /** Traffic distribution: load balancers, gateways, CDNs. */
    NETWORK,

    /** Request/response or stream processing: services, workers, functions. */
    COMPUTE,

    /** Durable or semi-durable stores: databases, caches, object stores. */
    STORAGE,

    /** Log-based decoupling: queues and topics. */
    MESSAGING,

    /** Identity, authentication and authorisation. */
    SECURITY,

    /** Metrics, logging and tracing planes. */
    OBSERVABILITY,

    /** Third-party dependencies outside the trust boundary. */
    EXTERNAL
}
