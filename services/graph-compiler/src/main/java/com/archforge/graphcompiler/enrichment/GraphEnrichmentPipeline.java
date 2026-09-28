package com.archforge.graphcompiler.enrichment;

import com.archforge.graphcompiler.compiler.InternalGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class GraphEnrichmentPipeline {

    private final NodeEnricher nodeEnricher;
    private final GraphMetadataGenerator metadataGenerator;

    public EnrichedGraph enrich(InternalGraph graph) {
        log.debug("Starting graph enrichment pipeline");

        Map<String, Map<String, Object>> enrichedNodes = nodeEnricher.enrichNodes(graph);
        Map<String, Object> graphMetadata = metadataGenerator.generateMetadata(graph);

        log.debug("Enrichment pipeline completed");

        return EnrichedGraph.builder()
                .enrichedNodes(enrichedNodes)
                .graphMetadata(graphMetadata)
                .build();
    }

    @lombok.Value
    @lombok.Builder
    public static class EnrichedGraph {
        Map<String, Map<String, Object>> enrichedNodes;
        Map<String, Object> graphMetadata;
    }
}
