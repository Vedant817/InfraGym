package com.archforge.design.ingest;

import com.archforge.contracts.canvas.Topology;
import com.archforge.contracts.event.EvaluationLifecycleEvent;
import com.archforge.contracts.event.SubmissionLifecycleEvent;
import com.archforge.contracts.event.SimulationSummaryEvent;
import com.archforge.contracts.event.ArchitectureEvaluationEvent;
import com.archforge.contracts.event.AggregatedMetricEvent;
import com.archforge.contracts.lifecycle.SubmissionStatus;
import com.archforge.design.domain.DesignEntity;
import com.archforge.design.domain.SubmissionEntity;
import com.archforge.design.repository.DesignRepository;
import com.archforge.design.repository.EvaluationRepository;
import com.archforge.design.repository.SubmissionEventRepository;
import com.archforge.design.repository.SubmissionRepository;
import com.archforge.design.repository.SimulationRepository;
import com.archforge.design.repository.NodeMetricRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Calls Spring-proxied Kafka entry points without an ambient test transaction. */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({SubmissionLifecycleListener.class, EvaluationListener.class, SimulationSummaryListener.class, LifecyclePersistenceTest.Beans.class})
class LifecyclePersistenceTest {
    private static PostgreSQLContainer<?> container;
    private static String url;
    private static String user;
    private static String password;
    private static final String SCHEMA = "revision_lifecycle_test_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired DesignRepository designs;
    @MockitoSpyBean SubmissionRepository submissions;
    @Autowired SubmissionEventRepository events;
    @Autowired SubmissionLifecycleListener lifecycle;
    @Autowired EvaluationListener evaluation;
    @Autowired SimulationSummaryListener summaries;
    @Autowired SimulationRepository simulations;
    @Autowired EvaluationRepository evaluations;
    @Autowired NodeMetricRepository metrics;
    @Autowired ObjectMapper mapper;
    private SubmissionEntity submission;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        url = System.getProperty("archforge.test.postgres-url");
        user = System.getProperty("archforge.test.postgres-user", "archforge");
        password = System.getProperty("archforge.test.postgres-password", "archforge");
        if (url == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
            url = container.getJdbcUrl(); user = container.getUsername(); password = container.getPassword();
        }
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        }
        properties.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (url != null) {
            try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
            }
        }
        if (container != null) container.stop();
    }

    @BeforeEach
    void seed() {
        reset(submissions);
        var design = designs.save(DesignEntity.create("lifecycle-test", "Lifecycle test", "", "normal", 0, 0));
        submission = submissions.save(SubmissionEntity.create(UUID.randomUUID(), design.getId(), "lifecycle-test", "normal",
                "", "{}", new Topology(List.of(), List.of()), Instant.now(), UUID.randomUUID()));
    }

    private SubmissionLifecycleEvent completed() {
        return new SubmissionLifecycleEvent(UUID.randomUUID().toString(), submission.getId().toString(),
                submission.getId().toString(), UUID.randomUUID().toString(), "lifecycle-test", submission.getDesignId().toString(),
                SubmissionLifecycleEvent.STAGE_EVALUATION, SubmissionStatus.COMPLETED, "Completed test evaluation", Map.of(), Instant.now());
    }

    @Test
    void lifecycleCommitsAuditAndTerminalStatusAndIgnoresRedelivery() throws Exception {
        var event = completed();
        String payload = mapper.writeValueAsString(event);
        lifecycle.onLifecycle(payload);
        lifecycle.onLifecycle(payload);
        var stored = submissions.findById(submission.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(SubmissionStatus.COMPLETED);
        assertThat(stored.getCompletedAt()).isNotNull();
        var audit = events.findBySubmissionIdOrderByOccurredAtAsc(submission.getId());
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().getStage()).isEqualTo("ai-evaluator");
        assertThat(audit.getFirst().getStatus()).isEqualTo("completed");
    }

    @Test
    void failedProjectionRollsBackTheAuditAppend() throws Exception {
        doThrow(new IllegalStateException("Projection unavailable")).when(submissions).save(any(SubmissionEntity.class));
        String payload = mapper.writeValueAsString(completed());
        assertThatThrownBy(() -> lifecycle.onLifecycle(payload)).hasMessage("Projection unavailable");
        assertThat(events.findBySubmissionIdOrderByOccurredAtAsc(submission.getId())).isEmpty();
        assertThat(submissions.findById(submission.getId()).orElseThrow().getStatus()).isEqualTo(SubmissionStatus.RECEIVED);
    }

    @Test
    void evaluationLifecycleAppendsWithARealTransaction() throws Exception {
        var event = EvaluationLifecycleEvent.of(submission.getId().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), submission.getId().toString(), "completed", "Evaluation persisted");
        String payload = mapper.writeValueAsString(event);
        evaluation.onEvaluationLifecycle(payload);
        evaluation.onEvaluationLifecycle(payload);
        assertThat(events.findBySubmissionIdOrderByOccurredAtAsc(submission.getId())).hasSize(1)
                .first().extracting(e -> e.getStage()).isEqualTo("evaluation.completed");
    }

    @Test
    void firstSummaryCreatesMeasurementsAndRedeliveryDoesNotDoubleTotals() throws Exception {
        String simulationId = UUID.randomUUID().toString();
        var node = new SimulationSummaryEvent.NodeSummary("api", "service", "API", 1, 2, 3, 4,
                0, 0, 10, 10, 0.5, 0, 0, false, false, 10);
        var window = new AggregatedMetricEvent(simulationId, submission.getId().toString(), "api", "service", "latency_ms", "ms",
                0, 10, Instant.now(), Instant.now(), 10, 1, 4, 2, 20, 1, 2, 3, 1);
        var summary = new SimulationSummaryEvent(simulationId, submission.getId().toString(), submission.getId().toString(),
                "normal", null, List.of(), List.of(node),
                new SimulationSummaryEvent.Totals(1, 2, 3, 0, 0, 10, 10, 100, 5, 10, 0, 0),
                List.of(window), Map.of(), Instant.now(), Instant.now(), 10, 10, 1000, List.of());
        String payload = mapper.writeValueAsString(summary);
        summaries.onSummary(payload);
        summaries.onSummary(payload);
        var stored = simulations.findById(UUID.fromString(simulationId)).orElseThrow();
        assertThat(stored.getSubmissionId()).isEqualTo(submission.getId());
        assertThat(stored.getOffered()).isEqualTo(100);
        assertThat(stored.getServed()).isEqualTo(85);
        assertThat(stored.getFailed()).isEqualTo(5);
        assertThat(stored.getQueued()).isEqualTo(10);
        assertThat(stored.reconciles()).isTrue();
        assertThat(metrics.findBySimulation(UUID.fromString(simulationId))).hasSize(1)
                .first().extracting(m -> m.p99Ms()).isEqualTo(3.0);
        assertThat(metrics.findWindowSeries(UUID.fromString(simulationId), "api", "latency_ms")).hasSize(1)
                .first().extracting(w -> w.sampleCount()).isEqualTo(10L);
        assertThat(submissions.findById(submission.getId()).orElseThrow().getStatus()).isEqualTo(SubmissionStatus.AGGREGATING);
    }

    @Test
    void persistedEvaluationCompletesSubmissionAndLateProgressCannotRewindIt() throws Exception {
        String simulationId = UUID.randomUUID().toString();
        UUID evaluationId = UUID.randomUUID();
        var result = new ArchitectureEvaluationEvent(UUID.randomUUID().toString(), submission.getId().toString(),
                evaluationId.toString(), simulationId, submission.getId().toString(), 82, "sound", "Measured result",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "none", 12, 0, 0, true, "No model", Instant.now());
        evaluation.onResult(mapper.writeValueAsString(result));
        assertThat(evaluations.findLatestForSubmission(submission.getId())).isPresent();
        var late = new SubmissionLifecycleEvent(UUID.randomUUID().toString(), submission.getId().toString(), submission.getId().toString(),
                simulationId, "lifecycle-test", submission.getDesignId().toString(), "simulation-engine", SubmissionStatus.SIMULATING,
                "Late progress", Map.of(), Instant.now());
        lifecycle.onLifecycle(mapper.writeValueAsString(late));
        var stored = submissions.findById(submission.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(SubmissionStatus.COMPLETED);
        assertThat(stored.getCompletedAt()).isNotNull();
    }

    @Test
    void lateSimulationProgressCannotRewindEvaluationInProgress() throws Exception {
        String simulationId = UUID.randomUUID().toString();
        evaluation.onEvaluationLifecycle(mapper.writeValueAsString(EvaluationLifecycleEvent.of(submission.getId().toString(),
                UUID.randomUUID().toString(), simulationId, submission.getId().toString(), "started", "Evaluating")));
        var late = new SubmissionLifecycleEvent(UUID.randomUUID().toString(), submission.getId().toString(), submission.getId().toString(),
                simulationId, "lifecycle-test", submission.getDesignId().toString(), "simulation-engine", SubmissionStatus.SIMULATING,
                "Late progress", Map.of(), Instant.now());
        lifecycle.onLifecycle(mapper.writeValueAsString(late));
        assertThat(submissions.findById(submission.getId()).orElseThrow().getStatus()).isEqualTo(SubmissionStatus.EVALUATING);
    }

    @Test
    void rejectionDetailSurvivesToTheAuditLogAndTheStageView() throws Exception {
        // The compiler publishes findings on a rejection; if the details are dropped
        // anywhere between the event and the DTO, the user is told only that
        // validation failed and never which node to fix.
        var event = new SubmissionLifecycleEvent(UUID.randomUUID().toString(), submission.getId().toString(),
                submission.getId().toString(), UUID.randomUUID().toString(), "lifecycle-test", submission.getDesignId().toString(),
                SubmissionLifecycleEvent.STAGE_COMPILER, SubmissionStatus.REJECTED,
                "Topology failed validation: Node 'obs' has unknown type 'metrics'.",
                Map.of("errors", List.of("Node 'obs' has unknown type 'metrics'."),
                        "findings", List.of(Map.of("field", "nodes[3].type", "code", "INVALID_NODE_TYPE",
                                "message", "Node 'obs' has unknown type 'metrics'."))),
                Instant.now());
        lifecycle.onLifecycle(mapper.writeValueAsString(event));
        var stored = submissions.findById(submission.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(SubmissionStatus.REJECTED);
        var stage = events.findBySubmissionIdOrderByOccurredAtAsc(submission.getId()).getFirst();
        assertThat(stage.getDetails()).contains("INVALID_NODE_TYPE");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean SimpleMeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
        @Bean org.springframework.jdbc.core.JdbcTemplate jdbcTemplate(javax.sql.DataSource source) {
            return new org.springframework.jdbc.core.JdbcTemplate(source);
        }
        @Bean EvaluationRepository evaluationRepository(org.springframework.jdbc.core.JdbcTemplate jdbc, ObjectMapper mapper) {
            return new EvaluationRepository(jdbc, mapper);
        }
        @Bean NodeMetricRepository nodeMetricRepository(org.springframework.jdbc.core.JdbcTemplate jdbc, ObjectMapper mapper) {
            return new NodeMetricRepository(jdbc, mapper);
        }
    }
}
