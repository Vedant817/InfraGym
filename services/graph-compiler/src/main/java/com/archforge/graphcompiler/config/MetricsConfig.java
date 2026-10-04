package com.archforge.graphcompiler.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Meter registration for the graph compiler.
 *
 * <p>Deliberately minimal. The previous version built a {@code Map<NodeType, Gauge>}
 * bean whose gauge read a counter nothing ever incremented — so it reported zero for
 * every node type, forever, on every deployment. A gauge nobody feeds is worse than no
 * gauge: it looks like data.
 *
 * <p>The node-type census is a {@link io.micrometer.core.instrument.Counter} instead,
 * incremented where node types are actually resolved ({@code DagCompiler}), so the
 * figure is true by construction. Counters are also the right shape for a
 * monotonically increasing quantity — a gauge invites resets and negative readings that
 * would have no meaning here.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfig {

    private final MeterRegistry meterRegistry;
    private final NodeTypeCounter nodeTypeCounter;

    public MetricsConfig(MeterRegistry meterRegistry,
                         @Value("${spring.application.name:graph-compiler}") String application) {
        this.meterRegistry = meterRegistry;
        this.nodeTypeCounter = new NodeTypeCounter(meterRegistry, application);
    }

    /**
     * Binds JVM processor metrics to this application's registry.
     *
     * <p>Not a {@code @Bean} of type {@code ProcessorMetrics}: that class's only
     * constructor takes a tag list and registers itself against
     * {@code Metrics.globalRegistry}, which is <em>not</em> this application's registry.
     * Declaring it as a bean type would create a bean whose meters never appeared at
     * {@code /actuator/metrics}. Binding explicitly to the injected registry is what
     * actually makes them visible.
     *
     * <p>Processor count is the first thing to check when compilation gets slow.
     */
    @PostConstruct
    void bindProcessorMetrics() {
        new ProcessorMetrics(java.util.List.of()).bindTo(meterRegistry);
    }

    /** Autowire point for components that need to record node-type statistics. */
    public NodeTypeCounter nodeTypeCounter() {
        return nodeTypeCounter;
    }

    /**
     * Records compiled nodes by type, and edges the builder refused.
     *
     * <p>A wrapper so call sites do not build tag lists.
     */
    public static final class NodeTypeCounter {
        private final MeterRegistry registry;
        private final String application;

        NodeTypeCounter(MeterRegistry registry, String application) {
            this.registry = registry;
            this.application = application;
        }

        public void recordCompiled(String nodeType, int count) {
            registry.counter("graph.compiler.nodes.compiled",
                    "application", application,
                    "nodeType", nodeType).increment(count);
        }

        public void recordRejectedEdge(String reason) {
            registry.counter("graph.compiler.edges.rejected",
                    "application", application,
                    "reason", reason).increment();
        }
    }
}
