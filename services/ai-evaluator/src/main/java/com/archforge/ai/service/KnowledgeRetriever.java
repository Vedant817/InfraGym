package com.archforge.ai.service;

import com.archforge.ai.config.EvaluatorProperties;
import com.archforge.contracts.event.ArchitectureEvaluationEvent;
import com.archforge.contracts.event.SimulationSummaryEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Retrieves reference architectures to give the model some background.
 *
 * <p><b>Disabled by default, and that is a decision rather than an omission.</b> The
 * previous service called {@code vectorStore.similaritySearch} on every evaluation with RAG
 * always on. With no documents indexed that returns an empty list, so all it accomplished
 * was an embedding round trip and a Postgres query per evaluation, in exchange for nothing.
 * Enabling it is one property away, and when it is enabled it reports what it did.
 *
 * <p><b>Failure is reported, not swallowed.</b> The old code caught every exception, logged a
 * warning and returned an empty list, so an unreachable vector store produced an evaluation
 * that looked identical to one with a fully-populated reference library. Here the failure
 * comes back to the caller as a note, which reaches the published event -- because "there
 * were no references" and "we could not reach the references" are different answers and the
 * reader is entitled to the difference.
 */
@Slf4j
@Component
public class KnowledgeRetriever {

    /**
     * What retrieval produced.
     *
     * @param references documents, possibly empty
     * @param note null when retrieval worked as configured, or when it was switched off; a
     *             sentence explaining any shortfall otherwise
     */
    public record Retrieval(List<ArchitectureEvaluationEvent.KnowledgeReference> references,
                            String note) {

        public Retrieval {
            references = references == null ? List.of() : List.copyOf(references);
        }

        public boolean degraded() {
            return note != null && !note.isBlank();
        }
    }

    private final ObjectProvider<VectorStore> vectorStore;
    private final EvaluatorProperties properties;
    private final MeterRegistry meterRegistry;

    public KnowledgeRetriever(ObjectProvider<VectorStore> vectorStore,
                              EvaluatorProperties properties,
                              MeterRegistry meterRegistry) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public Retrieval retrieve(SimulationSummaryEvent summary, int topK) {
        if (!properties.getRag().isEnabled()) {
            return new Retrieval(List.of(), null);
        }
        VectorStore store = vectorStore.getIfAvailable();
        if (store == null) {
            meterRegistry.counter("evaluator.rag.unavailable").increment();
            return new Retrieval(List.of(),
                    "Reference architectures were not consulted: no vector store is configured. "
                            + "The evaluation is unaffected -- the score and findings come from the "
                            + "simulation measurements, not from retrieved documents -- but the model "
                            + "had no background material.");
        }

        String query = EvaluationResultPublisher.retrievalQuery(summary);
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(properties.getRag().getMinSimilarity())
                    .build();
            List<Document> documents = store.similaritySearch(request);
            meterRegistry.counter("evaluator.rag.retrieved")
                    .increment(documents == null ? 0 : documents.size());

            List<ArchitectureEvaluationEvent.KnowledgeReference> references = new ArrayList<>();
            for (Document document : documents == null ? List.<Document>of() : documents) {
                references.add(toReference(document));
            }
            return new Retrieval(references, references.isEmpty()
                    ? String.format(Locale.ROOT,
                    "No reference architecture scored above the %.2f similarity threshold for this "
                            + "design, so the model was given no background material. A run with no "
                            + "references is not a worse evaluation -- the findings are measured -- but "
                            + "the narrative had less to reason from.",
                    properties.getRag().getMinSimilarity())
                    : null);
        } catch (Exception e) {
            meterRegistry.counter("evaluator.rag.failed").increment();
            log.warn("Reference retrieval failed for simulation {}: {}",
                    summary.simulationId(), e.getMessage());
            return new Retrieval(List.of(),
                    "Reference architectures could not be retrieved: the knowledge base was "
                            + "unreachable (" + e.getMessage() + "). The score and findings come from "
                            + "the simulation measurements and are unaffected.");
        }
    }

    /**
     * Projects a retrieved document onto the wire record.
     *
     * <p>Similarity is kept so a verdict is auditable: a reader who disagrees with a finding
     * can see that it was made in the presence of a reference that scored 0.91, or that it
     * was made with no references at all.
     */
    private ArchitectureEvaluationEvent.KnowledgeReference toReference(Document document) {
        Map<String, Object> metadata = document.getMetadata() == null
                ? Map.of() : document.getMetadata();
        // Document.getScore() carries the store's own similarity; metadata is the fallback
        // for a store that puts it there instead. Reading the accessor is what makes the
        // recorded similarity the actual similarity rather than a constant zero.
        double similarity = document.getScore() != null
                ? document.getScore()
                : Optional.ofNullable(metadata.get("score"))
                        .filter(Number.class::isInstance)
                        .map(value -> ((Number) value).doubleValue())
                        .orElse(0.0);
        String title = Optional.ofNullable(metadata.get("title"))
                .map(Object::toString)
                .orElseGet(() -> Optional.ofNullable(metadata.get("source"))
                        .map(Object::toString)
                        .orElse(document.getId() == null ? "(unnamed)" : document.getId()));
        String kind = Optional.ofNullable(metadata.get("kind"))
                .map(Object::toString)
                .orElse("reference");
        return new ArchitectureEvaluationEvent.KnowledgeReference(
                document.getId() == null ? title : document.getId(),
                kind, title, similarity, document.getText());
    }
}