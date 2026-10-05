package com.archforge.design.web;

import com.archforge.contracts.lifecycle.SubmissionStatus;
import com.archforge.design.domain.Evaluation;
import com.archforge.design.domain.NodeMetric;
import com.archforge.design.domain.SimulationEntity;
import com.archforge.design.domain.SubmissionEntity;
import com.archforge.design.repository.EvaluationRepository;
import com.archforge.design.repository.NodeMetricRepository;
import com.archforge.design.repository.SimulationRepository;
import com.archforge.design.repository.SubmissionEventRepository;
import com.archforge.design.repository.SubmissionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Not ready" must not be reported as "not found".
 *
 * <p>Both endpoints used to answer a bare 404 for three different situations: an id
 * that is not the caller's, a run that has not reached the stage which produces
 * results, and a run that will never produce them. For a user watching their own run
 * minutes after submitting it, the middle case read as "your submission has vanished",
 * which is alarming and wrong — it exists, the caller owns it, and the numbers are
 * simply not written yet.
 *
 * <p>Ownership deliberately stays a 404. A 403 would confirm the id exists, which is
 * exactly what an enumeration of somebody else's ids wants. The distinction between the
 * three cases is therefore carried by a machine-readable {@code code} on the problem
 * detail, which is what these tests assert.
 *
 * <p>A live Postgres is not required: the controller's behaviour here is a function of
 * the submission row and the repository answers, both supplied directly.
 */
class SubmissionResultsEndpointTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private SubmissionRepository submissions;
    private SimulationRepository simulations;
    private EvaluationRepository evaluations;
    private NodeMetricRepository metrics;
    private MockMvc mvc;
    private UUID submissionId;
    private String userId = "owner";

    @BeforeEach
    void wire() {
        submissions = mock(SubmissionRepository.class);
        simulations = mock(SimulationRepository.class);
        evaluations = mock(EvaluationRepository.class);
        metrics = mock(NodeMetricRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new SubmissionController(submissions,
                mock(SubmissionEventRepository.class), simulations, metrics, evaluations, objectMapper))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/").principal(new UsernamePasswordAuthenticationToken(userId, "n/a", List.of())))
                .build();
    }

    private SubmissionEntity submissionWith(SubmissionStatus status, UUID simulationId) {
        UUID designId = UUID.randomUUID();
        var submission = SubmissionEntity.create(submissionId, designId, userId, "flash_sale", "", "{}",
                new com.archforge.contracts.canvas.Topology(List.of(), List.of()), Instant.now(), UUID.randomUUID());
        // transitionTo is the only legal way to advance the status, as in production,
        // and it is forward-only: the path is walked in order.
        for (SubmissionStatus next : pathTo(status)) {
            submission.transitionTo(next);
        }
        if (simulationId != null) {
            submission.attachSimulation(simulationId);
        }
        return submission;
    }

    private List<SubmissionStatus> pathTo(SubmissionStatus target) {
        return switch (target) {
            case RECEIVED -> List.of();
            case VALIDATING -> List.of(SubmissionStatus.VALIDATING);
            case REJECTED -> List.of(SubmissionStatus.VALIDATING, SubmissionStatus.REJECTED);
            case SIMULATING -> List.of(SubmissionStatus.VALIDATING, SubmissionStatus.COMPILING,
                    SubmissionStatus.COMPILED, SubmissionStatus.SIMULATING);
            case AGGREGATING -> List.of(SubmissionStatus.VALIDATING, SubmissionStatus.COMPILING,
                    SubmissionStatus.COMPILED, SubmissionStatus.SIMULATING, SubmissionStatus.AGGREGATING);
            case COMPLETED -> List.of(SubmissionStatus.VALIDATING, SubmissionStatus.COMPILING,
                    SubmissionStatus.COMPILED, SubmissionStatus.SIMULATING, SubmissionStatus.AGGREGATING,
                    SubmissionStatus.EVALUATING, SubmissionStatus.COMPLETED);
            default -> List.of();
        };
    }

    private void owned(SubmissionEntity submission) {
        submissionId = submission.getId();
        when(submissions.findByIdAndUserId(submissionId, userId)).thenReturn(Optional.of(submission));
        when(simulations.findBySubmissionId(submissionId)).thenReturn(Optional.empty());
        when(metrics.findBySimulation(any())).thenReturn(List.of());
        when(evaluations.findLatestForSubmission(submissionId)).thenReturn(Optional.empty());
    }

    @Test
    void measurementsForARunStillCompilingSayNotReadyNotNotFound() throws Exception {
        var submission = submissionWith(SubmissionStatus.VALIDATING, null);
        owned(submission);

        mvc.perform(get("/api/v1/submissions/{id}/measurements", submissionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SubmissionController.MEASUREMENTS_NOT_READY))
                // The prose says why, in words a user can act on.
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("has not produced measurements")));
    }

    @Test
    void measurementsForARejectedRunSayNotReadyRatherThanClaimTheRunIsMissing() throws Exception {
        var submission = submissionWith(SubmissionStatus.REJECTED, null);
        owned(submission);

        mvc.perform(get("/api/v1/submissions/{id}/measurements", submissionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SubmissionController.MEASUREMENTS_NOT_READY));
    }

    @Test
    void measurementsForSomebodyElsesRunCarryNoCodeAtAll() throws Exception {
        var submission = submissionWith(SubmissionStatus.VALIDATING, null);
        owned(submission);
        // Belongs to another account: not returned, and deliberately not explained.
        when(submissions.findByIdAndUserId(submissionId, "someone-else")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/submissions/{id}/measurements", submissionId)
                .principal(new UsernamePasswordAuthenticationToken("someone-else", "n/a", List.of())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void measurementsForAReadyRunAreReturned() throws Exception {
        UUID simulationId = UUID.randomUUID();
        var submission = submissionWith(SubmissionStatus.COMPLETED, simulationId);
        owned(submission);
        SimulationEntity simulation = SimulationEntity.of(simulationId, submissionId, "flash_sale");
        simulation.apply("completed", 10, 10, 1000, 100, 90, 10, 0, "[]", "{}",
                Instant.now(), Instant.now());
        when(simulations.findBySubmissionId(submissionId)).thenReturn(Optional.of(simulation));
        when(metrics.findBySimulation(simulationId)).thenReturn(List.of());

        mvc.perform(get("/api/v1/submissions/{id}/measurements", submissionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulation.scenario").value("flash_sale"));
    }

    @Test
    void evaluationForARejectedRunSaysItWasRejectedAndWhy() throws Exception {
        var submission = submissionWith(SubmissionStatus.REJECTED, null);
        owned(submission);

        mvc.perform(get("/api/v1/submissions/{id}/evaluation", submissionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SubmissionController.EVALUATION_REJECTED))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("rejected")));
    }

    @Test
    void evaluationForARunStillRunningSaysNotReady() throws Exception {
        var submission = submissionWith(SubmissionStatus.SIMULATING, null);
        owned(submission);

        mvc.perform(get("/api/v1/submissions/{id}/evaluation", submissionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SubmissionController.EVALUATION_NOT_READY));
    }

    @Test
    void evaluationForAFinishedRunWithNoVerdictIsAPlainNotFound() throws Exception {
        var submission = submissionWith(SubmissionStatus.COMPLETED, UUID.randomUUID());
        owned(submission);

        // Terminal with genuinely nothing to return: no code, because "not ready" would
        // be a lie and "rejected" would be wrong.
        mvc.perform(get("/api/v1/submissions/{id}/evaluation", submissionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void storedWarningsArriveAsAStringListRatherThanOneEncodedString() throws Exception {
        UUID simulationId = UUID.randomUUID();
        var submission = submissionWith(SubmissionStatus.COMPLETED, simulationId);
        owned(submission);
        // Exactly what the aggregator stores: a JSON array in a text column.
        String stored = "[\"This summary covers 2 of the run's 6 tick windows.\","
                + "\"This run's own totals do not reconcile.\"]";
        SimulationEntity simulation = SimulationEntity.of(simulationId, submissionId, "flash_sale");
        simulation.apply("completed", 10, 10, 1000, 100, 90, 10, 0, stored, "{}",
                Instant.now(), Instant.now());
        when(simulations.findBySubmissionId(submissionId)).thenReturn(Optional.of(simulation));

        mvc.perform(get("/api/v1/submissions/{id}/measurements", submissionId))
                .andExpect(status().isOk())
                // Two elements, each the plain sentence. The defect produced a single
                // element containing the whole array as escaped JSON text.
                .andExpect(jsonPath("$.simulation.warnings.length()").value(2))
                .andExpect(jsonPath("$.simulation.warnings[0]")
                        .value("This summary covers 2 of the run's 6 tick windows."))
                .andExpect(jsonPath("$.simulation.warnings[1]")
                        .value("This run's own totals do not reconcile."));
    }

    @Test
    void undecodableStoredWarningsAreReportedRatherThanSilentlyDropped() throws Exception {
        UUID simulationId = UUID.randomUUID();
        var submission = submissionWith(SubmissionStatus.COMPLETED, simulationId);
        owned(submission);
        SimulationEntity simulation = SimulationEntity.of(simulationId, submissionId, "flash_sale");
        simulation.apply("completed", 10, 10, 1000, 100, 90, 10, 0, "not json at all", "{}",
                Instant.now(), Instant.now());
        when(simulations.findBySubmissionId(submissionId)).thenReturn(Optional.of(simulation));

        // A caveat that vanishes silently is the failure the warnings field exists to
        // prevent, so unparseable content is surfaced rather than turned into an empty
        // list the reader cannot distinguish from "no warnings".
        mvc.perform(get("/api/v1/submissions/{id}/measurements", submissionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulation.warnings.length()").value(1))
                .andExpect(jsonPath("$.simulation.warnings[0]")
                        .value(org.hamcrest.Matchers.containsString("could not be decoded")));
    }

    @Test
    void emptyStoredWarningsAreAnEmptyListNotOneEmptyString() throws Exception {
        UUID simulationId = UUID.randomUUID();
        var submission = submissionWith(SubmissionStatus.COMPLETED, simulationId);
        owned(submission);
        SimulationEntity simulation = SimulationEntity.of(simulationId, submissionId, "flash_sale");
        simulation.apply("completed", 10, 10, 1000, 100, 90, 10, 0, "[]", "{}",
                Instant.now(), Instant.now());
        when(simulations.findBySubmissionId(submissionId)).thenReturn(Optional.of(simulation));

        mvc.perform(get("/api/v1/submissions/{id}/measurements", submissionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulation.warnings.length()").value(0));
    }

    @Test
    void stageEventsCarryTheirStructuredDetails() throws Exception {
        // The design service stores the lifecycle payload in a jsonb column but the
        // response dropped it, so a rejection's per-node findings never reached the UI.
        var submission = submissionWith(SubmissionStatus.REJECTED, null);
        owned(submission);

        var event = mock(com.archforge.design.domain.SubmissionEventEntity.class);
        when(event.getStage()).thenReturn("graph-compiler");
        when(event.getStatus()).thenReturn("rejected");
        when(event.getMessage()).thenReturn("Topology failed validation: Node 'obs' has unknown type 'metrics'.");
        when(event.getOccurredAt()).thenReturn(Instant.now());
        when(event.getReceivedAt()).thenReturn(Instant.now());
        when(event.getDetails()).thenReturn("{\"findings\":[{\"field\":\"nodes[3].type\","
                + "\"code\":\"INVALID_NODE_TYPE\",\"message\":\"Node 'obs' has unknown type 'metrics'.\"}]}");
        SubmissionEventRepository eventRepository = mock(SubmissionEventRepository.class);
        when(eventRepository.findBySubmissionIdOrderByOccurredAtAsc(submissionId)).thenReturn(List.of(event));
        when(eventRepository.findFirstBySubmissionIdOrderByOccurredAtDesc(submissionId)).thenReturn(Optional.of(event));

        MockMvc withEvents = MockMvcBuilders.standaloneSetup(new SubmissionController(submissions,
                eventRepository, simulations, metrics, evaluations, objectMapper))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/").principal(new UsernamePasswordAuthenticationToken(userId, "n/a", List.of())))
                .build();

        withEvents.perform(get("/api/v1/submissions/{id}", submissionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stages[0].status").value("rejected"))
                .andExpect(jsonPath("$.stages[0].details.findings[0].code").value("INVALID_NODE_TYPE"))
                .andExpect(jsonPath("$.stages[0].details.findings[0].field").value("nodes[3].type"));
    }
}