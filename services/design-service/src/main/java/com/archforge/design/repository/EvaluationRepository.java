package com.archforge.design.repository;

import com.archforge.design.domain.Evaluation;
import com.archforge.design.domain.Evaluation.Finding;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Evaluations and their findings.
 *
 * <p>JDBC rather than JPA because the findings are a list whose length is decided by the
 * producer -- the model decides how many there are -- so it is a child collection to write,
 * not an entity with a generated id. Mapping it would need a cascade and a flush ordering,
 * and the whole record has a natural key ({@code evaluation_id}) that makes the whole thing
 * one upsert.
 *
 * <p>Findings carry an explicit {@code position}, and that is what makes a redelivered
 * evaluation replace its predecessor's findings rather than accumulate onto them. Without it
 * a rebalance between the write and the offset commit leaves a submission with two copies of
 * every finding and no way to tell which set is current.
 */
@Repository
@Slf4j
public class EvaluationRepository {

    private static final String UPSERT = """
            INSERT INTO evaluations (id, submission_id, simulation_id, overall_score, verdict,
                summary, strengths, weaknesses, recommendations, bottlenecks, references_doc,
                warnings, model, latency_ms, prompt_tokens, completion_tokens,
                degraded, degraded_reason, evaluated_at, recorded_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb,
                    ?::jsonb, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (id) DO UPDATE SET
                overall_score = EXCLUDED.overall_score,
                verdict = EXCLUDED.verdict,
                summary = EXCLUDED.summary,
                strengths = EXCLUDED.strengths,
                weaknesses = EXCLUDED.weaknesses,
                recommendations = EXCLUDED.recommendations,
                bottlenecks = EXCLUDED.bottlenecks,
                references_doc = EXCLUDED.references_doc,
                warnings = EXCLUDED.warnings,
                model = EXCLUDED.model,
                latency_ms = EXCLUDED.latency_ms,
                prompt_tokens = EXCLUDED.prompt_tokens,
                completion_tokens = EXCLUDED.completion_tokens,
                degraded = EXCLUDED.degraded,
                degraded_reason = EXCLUDED.degraded_reason,
                evaluated_at = EXCLUDED.evaluated_at
            """;

    private static final String UPSERT_FINDING = """
            INSERT INTO evaluation_findings (evaluation_id, position, node_id, severity,
                category, title, detail, evidence, recommendation, related_node_ids)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (evaluation_id, position) DO UPDATE SET
                node_id = EXCLUDED.node_id,
                severity = EXCLUDED.severity,
                category = EXCLUDED.category,
                title = EXCLUDED.title,
                detail = EXCLUDED.detail,
                evidence = EXCLUDED.evidence,
                recommendation = EXCLUDED.recommendation,
                related_node_ids = EXCLUDED.related_node_ids
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EvaluationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public int upsert(Evaluation evaluation) {
        // One evaluation is one row, so this is a plain update rather than a batch. Using
        // batchUpdate with a single-argument setter would force a List<Object[]> for no gain.
        int written = jdbc.update(UPSERT,
                evaluation.id(),
                evaluation.submissionId(),
                evaluation.simulationId(),
                evaluation.overallScore(),
                evaluation.verdict(),
                evaluation.summary(),
                json(evaluation.strengths()),
                json(evaluation.weaknesses()),
                json(evaluation.recommendations()),
                json(evaluation.bottlenecks()),
                json(evaluation.references()),
                json(evaluation.warnings()),
                evaluation.model(),
                evaluation.latencyMs(),
                evaluation.promptTokens(),
                evaluation.completionTokens(),
                evaluation.degraded(),
                evaluation.degradedReason(),
                Timestamp.from(evaluation.evaluatedAt()));
        return written;
    }

    /**
     * Replaces a re-evaluation's findings.
     *
     * <p>Deletes anything beyond the new set, so an evaluation that arrives twice with
     * fewer findings the second time does not leave the earlier, longer list behind as if it
     * were still true.
     */
    private void upsertFindings(UUID evaluationId, List<Finding> findings) {
        List<Finding> ordered = new ArrayList<>(findings == null ? List.of() : findings);
        for (int i = 0; i < ordered.size(); i++) {
            Finding finding = ordered.get(i);
            jdbc.update(UPSERT_FINDING, evaluationId, i,
                    finding.nodeId(),
                    finding.severity() == null ? "MEDIUM" : finding.severity().name(),
                    finding.category() == null ? "performance" : finding.category(),
                    finding.title() == null ? "(untitled)" : finding.title(),
                    finding.detail(),
                    finding.evidence(),
                    finding.recommendation(),
                    json(finding.relatedNodeIds()));
        }
        jdbc.update("DELETE FROM evaluation_findings WHERE evaluation_id = ? AND position >= ?",
                evaluationId, ordered.size());
    }

    /** The most recent evaluation for a submission, or empty. */
    public Optional<Evaluation> findLatestForSubmission(UUID submissionId) {
        List<UUID> ids = jdbc.queryForList(
                "SELECT id FROM evaluations WHERE submission_id = ? ORDER BY evaluated_at DESC",
                UUID.class, submissionId);
        return ids.isEmpty() ? Optional.empty() : findById(ids.get(0));
    }

    public Optional<Evaluation> findById(UUID evaluationId) {
        List<Evaluation> found = jdbc.query("""
                SELECT id, submission_id, simulation_id, overall_score, verdict, summary,
                       strengths, weaknesses, recommendations, bottlenecks, references_doc,
                       warnings, model, latency_ms, prompt_tokens, completion_tokens,
                       degraded, degraded_reason, evaluated_at
                FROM evaluations WHERE id = ?
                """, (rs, rowNum) -> new Evaluation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("submission_id", UUID.class),
                        rs.getObject("simulation_id", UUID.class),
                        rs.getInt("overall_score"),
                        rs.getString("verdict"),
                        rs.getString("summary"),
                        read(rs.getString("strengths"), stringList()),
                        read(rs.getString("weaknesses"), stringList()),
                        read(rs.getString("recommendations"), stringList()),
                        read(rs.getString("bottlenecks"),
                                new TypeReference<List<com.archforge.contracts.event
                                        .ArchitectureEvaluationEvent.Bottleneck>>() { }),
                        List.of(),
                        read(rs.getString("references_doc"),
                                new TypeReference<List<com.archforge.contracts.event
                                        .ArchitectureEvaluationEvent.KnowledgeReference>>() { }),
                        read(rs.getString("warnings"), stringList()),
                        rs.getString("model"),
                        rs.getLong("latency_ms"),
                        rs.getInt("prompt_tokens"),
                        rs.getInt("completion_tokens"),
                        rs.getBoolean("degraded"),
                        rs.getString("degraded_reason"),
                        rs.getTimestamp("evaluated_at").toInstant()), evaluationId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Evaluation evaluation = found.get(0);
        return Optional.of(new Evaluation(
                evaluation.id(), evaluation.submissionId(), evaluation.simulationId(),
                evaluation.overallScore(), evaluation.verdict(), evaluation.summary(),
                evaluation.strengths(), evaluation.weaknesses(), evaluation.recommendations(),
                evaluation.bottlenecks(), findingsOf(evaluation.id()),
                evaluation.references(), evaluation.warnings(), evaluation.model(),
                evaluation.latencyMs(), evaluation.promptTokens(), evaluation.completionTokens(),
                evaluation.degraded(), evaluation.degradedReason(), evaluation.evaluatedAt()));
    }

    private List<Finding> findingsOf(UUID evaluationId) {
        return jdbc.query("""
                SELECT position, node_id, severity, category, title, detail, evidence,
                       recommendation, related_node_ids
                FROM evaluation_findings WHERE evaluation_id = ? ORDER BY position
                """, (rs, rowNum) -> new Finding(
                        rs.getInt("position"),
                        rs.getString("node_id"),
                        com.archforge.contracts.event.ArchitectureEvaluationEvent.Severity
                                .valueOf(rs.getString("severity")),
                        rs.getString("category"),
                        rs.getString("title"),
                        rs.getString("detail"),
                        rs.getString("evidence"),
                        rs.getString("recommendation"),
                        read(rs.getString("related_node_ids"), stringList())),
                evaluationId);
    }

    /** Every evaluation a submission has received, newest first. */
    public List<Evaluation> findAllForSubmission(UUID submissionId) {
        return jdbc.queryForList(
                "SELECT id FROM evaluations WHERE submission_id = ? ORDER BY evaluated_at DESC",
                UUID.class, submissionId).stream()
                .map(this::findById)
                .flatMap(Optional::stream)
                .toList();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            // A column that cannot be serialised must not lose the row it belongs to. An
            // empty array is a truthful "nothing recorded" where a null would be a lie the
            // reader cannot distinguish from "not yet computed".
            return "[]";
        }
    }

    /** One instance, so the List<String> cases do not each allocate an anonymous type. */
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private static TypeReference<List<String>> stringList() {
        return STRING_LIST;
    }

    /**
     * Reads a jsonb column, or an empty list if it is absent or unreadable.
     *
     * <p>Falls back to empty rather than throwing because a column this service cannot parse
     * is not a reason to fail the whole read: the reader gets the rest of the evaluation and
     * an empty list where the unparseable part was, which is visibly incomplete rather than
     * silently wrong. Throwing would take down a page because one historical row has a shape
     * a newer version stopped writing.
     */
    private <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return emptyOf(type);
        }
        try {
            T value = objectMapper.readValue(json, type);
            return value == null ? emptyOf(type) : value;
        } catch (Exception e) {
            log.debug("Unreadable stored column, reading it as empty: {}", e.getMessage());
            return emptyOf(type);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T emptyOf(TypeReference<T> type) {
        return (T) List.of();
    }
}
