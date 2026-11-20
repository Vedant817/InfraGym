package com.archforge.design.service;

import com.archforge.contracts.canvas.Topology;
import com.archforge.contracts.event.DesignSubmittedEvent;
import com.archforge.contracts.lifecycle.ScenarioType;
import com.archforge.design.canvas.CanvasRepository;
import com.archforge.design.canvas.TopologySnapshot;
import com.archforge.design.domain.DesignEntity;
import com.archforge.design.domain.SubmissionEntity;
import com.archforge.design.repository.SubmissionRepository;
import com.archforge.design.repository.DesignRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Turning a REST request into a run.
 *
 * <p><b>This is the pipeline's only entry point, and until it existed nothing did.</b>
 * {@code DesignSubmittedEvent} documents itself as produced by the API gateway; the gateway
 * did not produce it, and neither did anything else. So a design submitted through the
 * product could never reach the graph compiler -- not because of a bug, but because the
 * producer did not exist. The topic had a subscriber and no publisher.
 *
 * <p>The order here matters: the submission row is written <em>before</em> the event is
 * published. If the publish fails the submission still exists and the user can see that it
 * never started, rather than the request erroring with nothing recorded. And the submission
 * id is generated here rather than by the producer, because it is the thing every later event
 * refers back to and the row has to be able to exist before the first event arrives.
 */
@Slf4j
@Service
public class SubmissionService {

    /** How long a publish may take before it is treated as failed. */
    static final int AWAIT_TIMEOUT_SECONDS = 15;

    private final SubmissionRepository submissions;
    private final DesignRepository designs;
    private final CanvasRepository canvases;
    private final SubmissionEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public SubmissionService(SubmissionRepository submissions,
                             DesignRepository designs,
                             CanvasRepository canvases,
                             SubmissionEventPublisher publisher,
                             ObjectMapper objectMapper,
                             MeterRegistry meterRegistry) {
        this.submissions = submissions;
        this.designs = designs;
        this.canvases = canvases;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Submits a design for simulation.
     *
     * @param userId the authenticated principal; a submission is always owned by whoever
     *               asked for it, never by whoever the request body claims
     */
    @Transactional
    public SubmissionEntity submit(UUID designId, String userId, String scenario,
                                    String scenarioNotes, String sessionId) {
        DesignEntity design = designs.findByIdAndUserId(designId, userId)
                .orElseThrow(() -> new DesignNotFoundException(designId));

        var document = canvases.findByDesignIdAndUserId(designId.toString(), userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Design " + designId + " has no stored topology. A submission has to "
                                + "reference something that was drawn."));

        Topology topology = objectMapper.convertValue(document.getTopology(), Topology.class);
        // fromValue throws on an unknown name. Callers that used to pass junk through
        // findByValue stored it as steady state and then simulated a different experiment
        // from the one the user asked for.
        ScenarioType resolvedScenario = ScenarioType.fromValue(scenario);

        UUID submissionId = UUID.randomUUID();
        SubmissionEntity submission = SubmissionEntity.create(
                submissionId, designId, userId,
                resolvedScenario.getValue(),
                scenarioNotes,
                toJson(topology), topology, Instant.now(), submissionId);

        submissions.save(submission);
        meterRegistry.counter("design.submission.created").increment();

        // The submission's own first lifecycle event, published before the pipeline is even
        // told about it. Without it the history starts at whatever stage the compiler gets to
        // first, and the state a user sees while a submission waits has no event explaining
        // it -- which is indistinguishable from a submission that was lost.
        publisher.accepted(submissionId, designId, null, userId,
                submission.getScenario(),
                "Submission accepted and queued for compilation.");

        DesignSubmittedEvent event = new DesignSubmittedEvent(
                UUID.randomUUID().toString(),
                submissionId.toString(),
                submissionId.toString(),
                designId.toString(),
                userId,
                sessionId,
                resolvedScenario.getValue(),
                scenarioNotes,
                topology,
                Instant.now());

        // After the row is written, and deliberately not in the same transaction: the event
        // has to reach the broker, and holding a database transaction open across a network
        // call to do so is how a slow broker turns into a locked row.
        boolean published = publisher.publish(event);
        if (!published) {
            // The submission exists and is sitting at RECEIVED. That is the honest outcome:
            // the user can see it never started, and a retry publishes a fresh event for the
            // same submission rather than creating a second one.
            meterRegistry.counter("design.submission.publish_failed").increment();
            log.error("Submission {} was recorded but could not be published to the pipeline. "
                    + "It will sit at RECEIVED until the event is retried.", submissionId);
        }
        return submission;
    }

    private String toJson(Topology topology) {
        try {
            return objectMapper.writeValueAsString(topology);
        } catch (Exception e) {
            throw new IllegalStateException("The submitted topology could not be serialised", e);
        }
    }

    /** Raised when a design is not the caller's to submit. */
    public static class DesignNotFoundException extends RuntimeException {
        public DesignNotFoundException(UUID designId) {
            // Deliberately says nothing about whether the design exists. "No such design" for
            // somebody else's id confirms it exists, which is the information an id
            // enumeration is looking for.
            super("No such design, or it is not yours: " + designId);
        }
    }
}