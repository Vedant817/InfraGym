package com.archforge.ai.service;

import com.archforge.ai.analysis.ArchitectureAnalysis;
import com.archforge.ai.config.EvaluatorProperties;
import com.archforge.ai.prompt.EvaluationPrompt;
import com.archforge.contracts.event.ArchitectureEvaluationEvent;
import com.archforge.contracts.event.SimulationSummaryEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The narrative half, backed by a real model.
 *
 * <p><b>Constrained to JSON rather than parsed out of prose.</b> The previous implementation
 * asked for {@code SCORE: 82\nSTRENGTHS: a, b, c} and scanned the reply line by line. Any
 * model that wrapped its answer in a markdown fence, wrote {@code SCORE: 82/100}, or
 * prefaced the output with "Sure, here is my evaluation" produced a score of the literal
 * string {@code 50} and three empty lists -- silently, with no error anywhere. A design with
 * a P99 of three seconds was graded as middling because of how the reply was formatted.
 *
 * <p>Here the model is handed the JSON schema and its reply is converted into a typed record.
 * A reply that does not fit becomes a narrative-less degraded evaluation, which is a visible
 * failure rather than a wrong number.
 *
 * <p><b>The model cannot touch the measurements.</b> It receives the computed score and the
 * established findings as facts and is asked only for prose and any <em>additional</em>
 * findings. Nothing on this path can alter a score, a bottleneck or a measurement, so "the
 * model raised the score" is not a failure mode that can be introduced here.
 */
@Slf4j
public class SpringAiNarrativeModel implements NarrativeModel {

    /** What the model is asked to return, and what it is converted into. */
    record ModelVerdict(
            String summary,
            String verdictRationale,
            List<ModelFinding> additionalFindings,
            String questionForUser) {

        ModelVerdict {
            if (additionalFindings == null) {
                additionalFindings = List.of();
            }
        }
    }

    record ModelFinding(
            String title,
            String severity,
            String category,
            String detail,
            String evidence,
            String recommendation,
            List<String> relatedNodeIds) {
    }

    private final ChatClient chatClient;
    private final EvaluatorProperties properties;
    private final ObjectMapper objectMapper;

    public SpringAiNarrativeModel(ChatClient chatClient,
                                  EvaluatorProperties properties,
                                  ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String modelName() {
        EvaluatorProperties.Model model = properties.getModel();
        return model.getName() == null || model.getName().isBlank()
                ? "configured model" : model.getName();
    }

    @Override
    public boolean available() {
        return chatClient != null;
    }

    @Override
    public Narrative narrate(SimulationSummaryEvent summary,
                             ArchitectureAnalysis analysis,
                             List<ArchitectureEvaluationEvent.KnowledgeReference> references) {
        long startedAt = System.nanoTime();
        String prompt = EvaluationPrompt.user(summary, analysis, references);

        try {
            ChatResponse response = chatClient.prompt()
                    .system(EvaluationPrompt.system())
                    .user(prompt)
                    .options(options())
                    .call()
                    .chatResponse();

            String text = response == null || response.getResult() == null
                    || response.getResult().getOutput() == null
                    ? null : response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                return Narrative.unavailable(
                        "No narrative was generated: the model returned an empty response. The "
                                + "score, verdict and findings below are the complete arithmetic "
                                + "evaluation and are unaffected.");
            }

            ModelVerdict verdict = parse(text);
            if (verdict == null) {
                // Not an exception: a model that returned prose instead of the requested
                // shape is a normal occurrence, not an outage. The arithmetic still stands.
                return new Narrative(truncate(text, 2_000), "", List.of(), null,
                        elapsedMillis(startedAt), promptTokens(response), completionTokens(response),
                        modelName());
            }

            return new Narrative(
                    verdict.summary(),
                    verdict.verdictRationale(),
                    toFindings(verdict.additionalFindings()),
                    verdict.questionForUser(),
                    elapsedMillis(startedAt),
                    promptTokens(response),
                    completionTokens(response),
                    modelName());
        } catch (Exception e) {
            log.warn("Model call failed: {}", e.toString());
            return Narrative.unavailable(
                    "No narrative was generated: the model call failed (" + e.getMessage()
                            + "). The score, verdict and findings below are the complete "
                            + "arithmetic evaluation and are unaffected.");
        }
    }

    /**
     * Converts the reply into the typed verdict.
     *
     * <p>Fenced code blocks are stripped first, because a model that correctly emits the
     * requested JSON inside a ```json fence has still answered the question -- and failing to
     * parse that would be exactly the "phrasing differently means score 50" failure the
     * structured output exists to remove.
     *
     * @return null when the reply could not be understood at all
     */
    private ModelVerdict parse(String text) {
        String json = stripFences(text);
        try {
            return objectMapper.readValue(json, ModelVerdict.class);
        } catch (Exception first) {
            // Some models emit the object wrapped in prose. Take the outermost braces rather
            // than discarding the answer outright.
            int open = json.indexOf('{');
            int close = json.lastIndexOf('}');
            if (open < 0 || close <= open) {
                return null;
            }
            try {
                return objectMapper.readValue(json.substring(open, close + 1), ModelVerdict.class);
            } catch (Exception e) {
                log.debug("Model reply did not match the requested shape: {}", e.getMessage());
                return null;
            }
        }
    }

    private static String stripFences(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, lastFence).trim();
    }

    /**
     * Turns the model's findings into wire findings.
     *
     * <p>Findings without evidence are dropped rather than published. The contract's own
     * documentation says every finding is tied to evidence, and an unevidenced finding in an
     * engineering report is a guess wearing the same clothes as a measurement -- which is
     * precisely the failure mode this service exists to remove, reintroduced through the
     * model.
     */
    static List<ArchitectureEvaluationEvent.Finding> toFindings(List<ModelFinding> modelFindings) {
        List<ArchitectureEvaluationEvent.Finding> findings = new ArrayList<>();
        for (ModelFinding finding : modelFindings == null ? List.<ModelFinding>of() : modelFindings) {
            if (finding == null || isBlank(finding.title()) || isBlank(finding.evidence())) {
                continue;
            }
            findings.add(new ArchitectureEvaluationEvent.Finding(
                    "model-" + slug(finding.title()),
                    parseSeverity(finding.severity()),
                    finding.category() == null ? "design" : finding.category(),
                    finding.title(),
                    finding.detail() == null ? "" : finding.detail(),
                    finding.evidence(),
                    finding.recommendation(),
                    finding.relatedNodeIds()));
        }
        return findings;
    }

    private static ArchitectureEvaluationEvent.Severity parseSeverity(String value) {
        if (value == null) {
            return ArchitectureEvaluationEvent.Severity.MEDIUM;
        }
        try {
            return ArchitectureEvaluationEvent.Severity.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // An unrecognised severity is not worth failing an evaluation over, but it is not
            // worth silently reading as MEDIUM either -- INFO is the honest floor for a claim
            // the model could not grade.
            return ArchitectureEvaluationEvent.Severity.LOW;
        }
    }

    private OpenAiChatOptions options() {
        EvaluatorProperties.Model model = properties.getModel();
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .temperature(model.getTemperature() == null ? 0.2 : model.getTemperature())
                .maxTokens(model.getMaxTokens() == null ? 2_000 : model.getMaxTokens());
        if (model.getName() != null && !model.getName().isBlank()) {
            builder.model(model.getName());
        }
        return builder.build();
    }

    private static int promptTokens(ChatResponse response) {
        Usage usage = usage(response);
        return usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
    }

    private static int completionTokens(ChatResponse response) {
        Usage usage = usage(response);
        return usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
    }

    private static Usage usage(ChatResponse response) {
        // Null at every level is normal: a provider that does not report usage leaves the
        // metadata empty, and a record of 0 tokens is honest where a guess would not be.
        return response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + " [...]";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String slug(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}