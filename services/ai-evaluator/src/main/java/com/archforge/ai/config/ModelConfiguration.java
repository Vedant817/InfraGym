package com.archforge.ai.config;

import com.archforge.ai.service.NarrativeModel;
import com.archforge.ai.service.SpringAiNarrativeModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Builds the model, and only when there is a model to build.
 *
 * <p><b>Why this exists rather than relying on Spring AI's autoconfiguration.</b>
 * {@code OpenAiChatAutoConfiguration} calls {@code Assert.hasText} on the API key while
 * building its beans, so a missing or blank key is a <em>startup</em> failure rather than a
 * runtime one. That would make the whole service unable to boot without a model credential --
 * so the deterministic half, which needs no credential and is the half a reader is asked to
 * trust, would be unavailable precisely when the model is misconfigured. A misconfigured
 * model should cost you the narrative, not the measurements.
 *
 * <p>So the OpenAI and ChatClient autoconfigurations are excluded (see {@code application.yml})
 * and the client is built here, conditional on a key being present. No key, no client, and
 * {@link NoModelNarrativeModel} takes its place: the service starts, consumes, and publishes
 * a complete arithmetic evaluation flagged {@code degraded} with the reason on the record.
 *
 * <p>With a key present the full path lights up and nothing else changes.
 */
@Slf4j
@Configuration
public class ModelConfiguration {

    /**
     * The chat client, built only when a key is configured.
     *
     * <p>Conditional on {@code archforge.evaluator.model.credential-present} rather than on
     * the key property directly, because an environment variable that exists but resolved to
     * an empty string is the common misconfiguration -- an unset variable in a compose file,
     * a masked secret that decrypted to nothing -- and reading it as "a key is configured"
     * would reintroduce exactly the startup failure described above. The flag is set in
     * {@code application.yml} from the key's own presence.
     */
    @Bean
    @ConditionalOnProperty(prefix = "archforge.evaluator.model", name = "credential-present",
            havingValue = "true")
    public ChatClient archforgeChatClient(
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
            EvaluatorProperties properties) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(defaultOptions(properties))
                .build();
        log.info("Model configured: {}", properties.getModel().getName().isBlank()
                ? "provider default" : properties.getModel().getName());
        return ChatClient.builder(model).build();
    }

    private static OpenAiChatOptions defaultOptions(EvaluatorProperties properties) {
        EvaluatorProperties.Model model = properties.getModel();
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .temperature(model.getTemperature() == null ? 0.2 : model.getTemperature())
                .maxTokens(model.getMaxTokens() == null ? 2_000 : model.getMaxTokens());
        if (model.getName() != null && !model.getName().isBlank()) {
            builder.model(model.getName());
        }
        return builder.build();
    }

    /**
     * The Spring-backed model, when a client was built.
     *
     * <p>Ordered before the fallback so the two are mutually exclusive: {@link
     * ConditionalOnMissingBean} on the fallback makes exactly one of them register, which is
     * what keeps the service's single {@code NarrativeModel} dependency unambiguous.
     */
    @Bean
    @ConditionalOnProperty(prefix = "archforge.evaluator.model", name = "credential-present",
            havingValue = "true")
    @ConditionalOnMissingBean(NarrativeModel.class)
    public NarrativeModel springAiNarrativeModel(ChatClient chatClient,
                                                 EvaluatorProperties properties,
                                                 ObjectMapper objectMapper) {
        return new SpringAiNarrativeModel(chatClient, properties, objectMapper);
    }

    /** The fallback: no model, and an evaluation that says so. */
    @Bean
    @ConditionalOnMissingBean(NarrativeModel.class)
    public NoModelNarrativeModel noModelNarrativeModel() {
        log.info("No model credential is configured. The evaluator will publish the deterministic "
                + "analysis for every run, flagged degraded. This is the intended fallback, not a "
                + "reduced mode: the score, verdict, bottlenecks and every finding come from the "
                + "simulation's measurements and need no model.");
        return new NoModelNarrativeModel();
    }

    /**
     * The narrative implementation used when no model is configured.
     *
     * <p>Reports itself unavailable rather than throwing, so the service's degradation path is
     * the same code whether the model is switched off, unconfigured, or broken -- and so that
     * path is reachable in tests without standing up a provider.
     */
    public static class NoModelNarrativeModel implements NarrativeModel {

        @Override
        public Narrative narrate(com.archforge.contracts.event.SimulationSummaryEvent summary,
                                 com.archforge.ai.analysis.ArchitectureAnalysis analysis,
                                 List<com.archforge.contracts.event.ArchitectureEvaluationEvent
                                         .KnowledgeReference> references) {
            return Narrative.unavailable(
                    "No narrative was generated: no language model is configured. Everything above "
                            + "is derived directly from the simulation's measurements, so the score, "
                            + "the verdict, the bottlenecks and every finding stand as they are. What "
                            + "is missing is the interpretation: nothing here explains the design's "
                            + "intent, its trade-offs, or what a different architecture would have "
                            + "done with the same load. That requires a model, and it is absent "
                            + "rather than wrong.");
        }

        @Override
        public String modelName() {
            return "none";
        }

        @Override
        public boolean available() {
            return false;
        }
    }
}