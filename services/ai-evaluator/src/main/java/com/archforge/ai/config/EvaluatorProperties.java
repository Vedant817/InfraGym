package com.archforge.ai.config;

import com.archforge.ai.analysis.DeterministicEvaluator;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How this service evaluates.
 *
 * <p>The previous implementation had its thresholds nowhere: it asked a model to "identify
 * the primary bottleneck" with no threshold to identify it against, which is a request for
 * an opinion. Every number the score depends on is here instead, with a default and a
 * reason, and the defaults are the ones the analysis documents.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "archforge.evaluator")
public class EvaluatorProperties {

    /** Kafka consumer group. Distinct from every other service's, or they steal records. */
    private String consumerGroup = "ai-evaluator-group";

    /** Whether the model is consulted at all. Off produces a metrics-only evaluation. */
    private boolean modelEnabled = true;

    /**
     * How long the model may take before the deterministic evaluation is published instead.
     *
     * <p>Bounded on purpose. The deterministic half answers the question the user asked
     * within milliseconds of the run finishing; making them wait minutes for prose about it
     * is strictly worse than publishing the measurements and a short verdict now.
     */
    private long modelTimeoutMillis = 20_000;

    private final DeterministicEvaluator.Thresholds thresholds = DeterministicEvaluator.Thresholds.defaults();

    private final Rag rag = new Rag();

    private final Model model = new Model();

    /**
     * Retrieval for the architecture reference library.
     *
     * <p>Disabled by default, and that is a considered choice rather than an unfinished
     * feature: an empty vector store returns nothing, so enabling it by default would add a
     * Postgres connection, a table and an embedding round trip per evaluation to produce an
     * empty list. It is a real capability for once the library has documents in it, and it
     * reports its own failure on the published event rather than hiding it.
     */
    @Getter
    @Setter
    public static class Rag {

        private boolean enabled = false;

        /** Documents retrieved per evaluation. */
        private int topK = 4;

        /** Below this similarity a document is noise and is dropped. */
        private double minSimilarity = 0.65;

        /** Hard ceiling on retrieval, so a slow index cannot hold up an evaluation. */
        private long timeoutMillis = 2_000;
    }

    @Getter
    @Setter
    public static class Model {

        /** Overrides the auto-configured model id. */
        private String name = "";

        private Double temperature = 0.2;

        private Integer maxTokens = 2_000;
    }
}