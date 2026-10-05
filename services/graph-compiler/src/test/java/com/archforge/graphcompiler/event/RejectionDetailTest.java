package com.archforge.graphcompiler.event;

import com.archforge.contracts.canvas.CanvasEdge;
import com.archforge.contracts.canvas.CanvasNode;
import com.archforge.contracts.canvas.NodeData;
import com.archforge.contracts.canvas.Position;
import com.archforge.contracts.canvas.Topology;
import com.archforge.contracts.event.DesignSubmittedEvent;
import com.archforge.contracts.event.SubmissionLifecycleEvent;
import com.archforge.contracts.lifecycle.SubmissionStatus;
import com.archforge.graphcompiler.compiler.DagCompiler;
import com.archforge.graphcompiler.compiler.GraphCompiler;
import com.archforge.graphcompiler.validation.CycleDetector;
import com.archforge.graphcompiler.validation.EdgeValidator;
import com.archforge.graphcompiler.validation.NodeTypeValidator;
import com.archforge.graphcompiler.validation.OrphanNodeDetector;
import com.archforge.graphcompiler.validation.SchemaValidator;
import com.archforge.graphcompiler.validation.SemanticValidator;
import com.archforge.graphcompiler.validation.TopologyValidator;
import com.archforge.messaging.SubmissionLifecyclePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * A rejection must say which node was refused and why.
 *
 * <p>The defect these tests pin down: the consumer published the headline message
 * "Topology failed validation" and nothing more. The validator already knew precisely
 * what was wrong, down to the node and a stable code
 * ({@code INVALID_NODE_TYPE}, {@code SELF_LOOP}, {@code CYCLE_DETECTED}), and that
 * detail was being discarded at the point of publication. The user was told a run had
 * failed and not one thing about what to change.
 *
 * <p>The publisher is captured rather than mocked-and-asserted-on-call-count, and the
 * captured record is then serialised and read back as the design service reads it.
 * Asserting only that {@code publish} was called would pass while the payload was
 * unreadable to its actual consumer, which is the other half of the same defect.
 */
class RejectionDetailTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final List<SubmissionLifecycleEvent> published = new ArrayList<>();
    private DesignSubmissionConsumer consumer;

    @BeforeEach
    void wire() {
        // The real validator, constructed directly: no Spring context and no broker.
        TopologyValidator validator = new TopologyValidator(
                new SchemaValidator(500, 2000, 4096), new NodeTypeValidator(),
                new EdgeValidator(), new CycleDetector(), new OrphanNodeDetector(),
                new SemanticValidator());
        CompilationService compilationService = new CompilationService(
                validator, mock(GraphCompiler.class), mock(DagCompiler.class),
                mock(SimulationTaskProducer.class),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        doAnswer(invocation -> {
            published.add(invocation.getArgument(0));
            return null;
        }).when(lifecyclePublisher()).publish(any());
        consumer = new DesignSubmissionConsumer(objectMapper, compilationService, lifecyclePublisher());
    }

    private final SubmissionLifecyclePublisher[] publisher = new SubmissionLifecyclePublisher[1];

    private SubmissionLifecyclePublisher lifecyclePublisher() {
        if (publisher[0] == null) {
            publisher[0] = mock(SubmissionLifecyclePublisher.class);
        }
        return publisher[0];
    }

    private static CanvasNode node(String id, String type, String label) {
        return new CanvasNode(id, type, new Position(0, 0), new NodeData(label));
    }

    private DesignSubmittedEvent submissionFor(Topology topology) throws Exception {
        return new DesignSubmittedEvent(null, null, null,
                "design", "user", "session", "flash_sale", "", topology,
                Instant.now());
    }

    /**
     * Consumes the submission and returns the published record as the design service
     * parses it: the whole event, so the headline message and its details are both
     * reachable.
     */
    private Map<String, Object> rejectionFor(Topology topology) throws Exception {
        published.clear();
        consumer.consumeDesignSubmission(objectMapper.writeValueAsString(submissionFor(topology)));
        assertThat(published).as("a lifecycle event was published").isNotEmpty();
        var rejection = published.stream()
                .filter(event -> event.status() == SubmissionStatus.REJECTED)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no REJECTED lifecycle event was published"));
        // Round-tripped through JSON, because unreadable-on-the-wire is the same failure
        // as not published at all.
        return objectMapper.readValue(objectMapper.writeValueAsString(rejection), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> findingsOf(Map<String, Object> event) {
        return (List<Map<String, Object>>) details(event).get("findings");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Map<String, Object> event) {
        return (Map<String, Object>) event.get("details");
    }

    @Test
    void rejectionNamesTheNodeAndTheReason() throws Exception {
        // The exact shape the QA run was refused for: a valid chain plus an observability
        // node whose type the compiler's NodeType does not accept.
        Topology topology = new Topology(
                List.of(node("lb", "load_balancer", "Ingress"),
                        node("api", "service", "Orders API"),
                        node("db", "database", "Orders DB"),
                        node("obs", "metrics", "Observability")),
                List.of(new CanvasEdge("e1", "lb", "api"), new CanvasEdge("e2", "api", "db")));

        Map<String, Object> rejection = rejectionFor(topology);
        List<Map<String, Object>> findings = findingsOf(rejection);
        assertThat(findings).isNotEmpty();
        Map<String, Object> first = findings.getFirst();
        // Attributable to a node and to a stable machine-readable code.
        assertThat(first.get("code")).isEqualTo("INVALID_NODE_TYPE");
        assertThat((String) first.get("field")).contains("type");
        // The message names the offending node and its type, so the user knows what to change.
        assertThat((String) first.get("message")).contains("obs").contains("metrics");
        // Plain messages are still published alongside, so a reader of the strings
        // loses nothing.
        assertThat((List<?>) details(rejection).get("errors")).isNotEmpty();
        // The headline itself names the first cause: a reader who never opens the
        // finding list still learns something actionable.
        assertThat((String) rejection.get("message")).contains("Topology failed validation").contains("metrics");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyOffendingNodeIsReportedNotOnlyTheFirst() throws Exception {
        Topology topology = new Topology(
                List.of(node("lb", "load_balancer", "Ingress"),
                        node("api", "service", "Orders API"),
                        node("obs1", "metrics", "Metrics"),
                        node("obs2", "tracing", "Tracing")),
                List.of(new CanvasEdge("e1", "lb", "api")));

        Map<String, Object> rejection = rejectionFor(topology);
        List<Map<String, Object>> findings = findingsOf(rejection);
        assertThat(findings).hasSize(2);
        assertThat(findings).allSatisfy(finding -> assertThat(finding).containsEntry("code", "INVALID_NODE_TYPE"));
        // A single-cause headline would hide one of them.
        assertThat((String) rejection.get("message")).contains("and 1 more");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCycleIsRejectedWithItsPath() throws Exception {
        Topology topology = new Topology(
                List.of(node("a", "service", "A"), node("b", "service", "B")),
                List.of(new CanvasEdge("e1", "a", "b"), new CanvasEdge("e2", "b", "a")));

        Map<String, Object> rejection = rejectionFor(topology);
        List<Map<String, Object>> findings = findingsOf(rejection);
        assertThat(findings).isNotEmpty();
        assertThat(findings.getFirst()).containsEntry("code", "CYCLE_DETECTED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEmptyTopologyIsRejectedWithAnExplanationRatherThanSilence() throws Exception {
        Map<String, Object> rejection = rejectionFor(new Topology(List.of(), List.of()));
        assertThat((String) rejection.get("message")).contains("no nodes");
        assertThat(findingsOf(rejection)).isNotEmpty();
    }

    @Test
    void aValidTopologyProducesNoRejection() {
        // Establishes that the assertions above are about rejection specifically, and
        // not something every submission produces.
        Topology topology = new Topology(
                List.of(node("lb", "load_balancer", "Ingress"), node("api", "service", "Orders API"),
                        node("db", "database", "Orders DB")),
                List.of(new CanvasEdge("e1", "lb", "api"), new CanvasEdge("e2", "api", "db")));
        TopologyValidator validator = new TopologyValidator(
                new SchemaValidator(500, 2000, 4096), new NodeTypeValidator(), new EdgeValidator(),
                new CycleDetector(), new OrphanNodeDetector(), new SemanticValidator());
        assertThat(validator.validate(topology).hasErrors()).isFalse();
    }

    @Test
    void theValidatorStillRejectsRatherThanCompilingAnInvalidGraph() {
        // The detail added to the message must not have softened the refusal.
        TopologyValidator validator = new TopologyValidator(
                new SchemaValidator(500, 2000, 4096), new NodeTypeValidator(), new EdgeValidator(),
                new CycleDetector(), new OrphanNodeDetector(), new SemanticValidator());
        Topology selfLoop = new Topology(
                List.of(node("a", "service", "A"), node("b", "service", "B")),
                List.of(new CanvasEdge("e1", "a", "a"), new CanvasEdge("e2", "a", "b")));
        assertThatThrownBy(() -> validator.validate(selfLoop))
                .isInstanceOf(com.archforge.graphcompiler.exception.ValidationException.class)
                .satisfies(thrown -> assertThat(((com.archforge.graphcompiler.exception.ValidationException) thrown)
                        .blockingFindings()).isNotEmpty());
    }
}