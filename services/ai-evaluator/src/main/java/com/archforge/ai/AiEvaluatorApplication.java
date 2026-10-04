package com.archforge.ai;

import com.archforge.ai.config.EvaluatorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI evaluator: turns a finished simulation into an assessment.
 *
 * <p>Runs with or without a language model. With one, it publishes the arithmetic assessment
 * plus a narrative explaining it. Without one, it publishes the arithmetic assessment alone
 * and says on the record that the narrative is absent. Both are useful; only the second is
 * guaranteed, which is why the score is computed from the run's measurements rather than
 * asked for.
 *
 * <p>{@link EnableScheduling} is what bounds the evaluator's memory: the idempotency set and
 * the retrieved-reference cache are per-run state, and without a sweep they grow with every
 * run the process has ever handled.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(EvaluatorProperties.class)
public class AiEvaluatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiEvaluatorApplication.class, args);
    }
}