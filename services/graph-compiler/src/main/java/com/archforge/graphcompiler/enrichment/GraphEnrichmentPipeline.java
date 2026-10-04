package com.archforge.graphcompiler.enrichment;

import com.archforge.graphcompiler.compiler.InternalGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs enrichment and metadata generation over a built graph.
 *
 * <p>The previous version wrapped its result in a Lombok {@code @Builder} class with
 * generated getters, which adds a mutable intermediate for no benefit — a record is
 * both shorter and immutable by construction, which is what a value produced inside a
 * single pipeline step should be.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphEnrichmentPipeline {

    private final NodeEnricher nodeEnricher;
    private final GraphMetadataGenerator metadataGenerator;

    public EnrichedGraph enrich(InternalGraph graph) {
        Map<String, Map<String, Object>> nodes = nodeEnricher.enrichNodes(graph);
        Map<String, Object> metadata = metadataGenerator.generateMetadata(graph);

        log.debug("Enriched {} node(s); metadata keys: {}", nodes.size(), metadata.keySet());

        return new EnrichedGraph(
                Collections.unmodifiableMap(new LinkedHashMap<>(nodes)),
                Collections.unmodifiableMap(new LinkedHashMap<>(metadata)));
    }

    /**
     * @param enrichedNodes per-node simulation characteristics, keyed by node id
     * @param graphMetadata architecture-level summary
     */
    public record EnrichedGraph(
            Map<String, Map<String, Object>> enrichedNodes,
            Map<String, Object> graphMetadata) {
    }
}
