package com.archforge.design.web;

import com.archforge.design.canvas.CanvasDocument;
import com.archforge.design.canvas.CanvasRepository;
import com.archforge.design.canvas.TopologySnapshot;
import com.archforge.design.domain.DesignEntity;
import com.archforge.design.repository.DesignRepository;
import com.archforge.contracts.lifecycle.ScenarioType;
import com.archforge.design.service.SubmissionService;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A user's designs, and editing them.
 *
 * <p>Every handler takes the owner from the {@link Authentication} and never from the request
 * body. A body-supplied {@code userId} would let a caller write into somebody else's design
 * list simply by changing one field, and "the frontend always sends the right one" is not a
 * security property.
 *
 * <p>A design that is not the caller's returns 404, not 403. The distinction matters: a 403
 * confirms the id exists, which is exactly what an enumeration of somebody else's ids wants.
 */
@RestController
@RequestMapping("/api/v1/designs")
@Slf4j
public class DesignController {

    private final DesignRepository designs;
    private final CanvasRepository canvases;
    private final SubmissionService submissions;
    private final ObjectMapper objectMapper;

    public DesignController(DesignRepository designs, CanvasRepository canvases,
                            SubmissionService submissions, ObjectMapper objectMapper) {
        this.designs = designs;
        this.canvases = canvases;
        this.submissions = submissions;
        this.objectMapper = objectMapper;
    }

    /** Every workload the simulator can run, in declaration order. */
    @GetMapping("/scenarios")
    public List<Dtos.ScenarioOption> scenarios() {
        return ScenarioCatalog.list();
    }

    @GetMapping
    public List<Dtos.DesignSummary> list(Authentication authentication,
                                         @RequestParam(defaultValue = "50") int limit) {
        String userId = authentication.getName();
        Map<UUID, Long> revisions = new HashMap<>();
        for (CanvasDocument document : canvases.findByUserId(userId)) {
            revisions.put(UUID.fromString(document.getDesignId()), document.getRevision());
        }
        return designs.findByUserIdOrderByUpdatedAtDesc(userId, PageRequest.of(0, clamp(limit)))
                .stream()
                .map(design -> toSummary(design,
                        revisions.getOrDefault(design.getId(), 0L)))
                .toList();
    }

    @PostMapping
    public ResponseEntity<Dtos.DesignDetail> create(Authentication authentication,
                                                    @RequestBody Dtos.CreateDesignRequest request) {
        String userId = authentication.getName();
        if (request.topology() == null) {
            // Thrown rather than returned, so Spring renders an RFC 7807 problem detail with
            // the reason in it. A 400 with an empty body tells a client author nothing they
            // did not already know, and the fix is not obvious when the payload looked right.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A design needs a topology: topology was null or absent");
        }
        ScenarioType scenario = ScenarioCatalog.require(request.scenario());
        DesignEntity design = DesignEntity.create(userId, request.name(), request.description(),
                scenario.getValue(),
                request.topology().getNodes().size(), request.topology().getEdges().size());
        designs.save(design);
        canvases.create(design.getId().toString(), userId,
                TopologySnapshot.from(request.topology(), objectMapper));

        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag("\"1\"")
                .body(new Dtos.DesignDetail(design.getId(), design.getName(),
                        design.getDescription(), design.getScenario(), 1L, request.topology(),
                        design.getCreatedAt(), design.getUpdatedAt()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Dtos.DesignDetail> get(Authentication authentication,
                                                 @PathVariable UUID id) {
        String userId = authentication.getName();
        DesignEntity design = designs.findByIdAndUserId(id, userId).orElse(null);
        if (design == null) {
            return ResponseEntity.notFound().build();
        }
        var document = canvases.findByDesignIdAndUserId(id.toString(), userId).orElse(null);
        if (document == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().eTag("\"" + document.getRevision() + "\"")
                .body(new Dtos.DesignDetail(design.getId(), design.getName(),
                design.getDescription(), design.getScenario(), document.getRevision(),
                document.getTopology(), design.getCreatedAt(), design.getUpdatedAt()));
    }

    @PutMapping("/{id}/topology")
    public ResponseEntity<?> saveTopology(
            Authentication authentication,
            @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody Dtos.UpdateTopologyRequest request) {
        String userId = authentication.getName();
        DesignEntity design = designs.findByIdAndUserId(id, userId).orElse(null);
        if (design == null) {
            return ResponseEntity.notFound().build();
        }
        long expectedRevision = parseRevision(ifMatch);
        if (request.topology() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A topology save needs a topology: topology was null or absent");
        }
        var document = canvases.compareAndSetRevision(id.toString(), userId, expectedRevision,
                TopologySnapshot.from(request.topology(), objectMapper)).orElse(null);
        if (document == null) {
            var current = canvases.findByDesignIdAndUserId(id.toString(), userId).orElse(null);
            if (current == null) {
                return ResponseEntity.notFound().build();
            }
            ProblemDetail conflict = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "Topology was not saved: expected revision " + expectedRevision
                            + ", but stored revision is " + current.getRevision()
                            + ". Reload the design before saving again. Local edits can be exported.");
            conflict.setTitle("Topology revision conflict");
            conflict.setProperty("expectedRevision", expectedRevision);
            conflict.setProperty("storedRevision", current.getRevision());
            conflict.setProperty("reloadRequired", true);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .eTag("\"" + current.getRevision() + "\"").body(conflict);
        }
        design.topologyChanged(request.topology().getNodes().size(),
                request.topology().getEdges().size());
        designs.save(design);

        return ResponseEntity.ok().eTag("\"" + document.getRevision() + "\"")
                .body(new Dtos.DesignDetail(design.getId(), design.getName(),
                design.getDescription(), design.getScenario(),
                document.getRevision(), document.getTopology(),
                design.getCreatedAt(), design.getUpdatedAt()));
    }

    private static long parseRevision(String ifMatch) {
        if (ifMatch == null) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required: send the quoted revision from the loaded design");
        }
        if (!ifMatch.matches("\"[0-9]+\"")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "If-Match must be a single strong, quoted numeric revision, for example \"1\"");
        }
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match revision is too large");
        }
    }

    /**
     * Submits a design for simulation. This is the pipeline's entry point.
     *
     * <p>Returns 202 with the submission, not 200 with nothing: the run has not happened yet
     * and a client that treats this as synchronous will poll for a simulation that is seconds
     * away. The response body carries the submission id, which is what a client then watches.
     */
    @PostMapping("/{id}/submissions")
    public ResponseEntity<Dtos.SubmissionSummary> submit(
            Authentication authentication,
            @PathVariable UUID id,
            @RequestBody(required = false) Dtos.SubmitRequest request) {
        String userId = authentication.getName();
        Dtos.SubmitRequest body = request == null
                ? new Dtos.SubmitRequest(null, null, null) : request;
        // Resolved before the try. The catches below are for a missing design and a
        // design with no topology. An unknown workload is a 400, and folding it into
        // either of those would report the wrong failure.
        ScenarioType scenario = ScenarioCatalog.require(body.scenario());
        try {
            var submission = submissions.submit(id, userId, scenario.getValue(),
                    body.scenarioNotes(), body.sessionId());
            return ResponseEntity.accepted()
                    .body(new Dtos.SubmissionSummary(submission.getId(), submission.getDesignId(),
                            submission.getSimulationId(), submission.getStatus().getValue(),
                            submission.getScenario(), submission.getScenarioNotes(),
                            submission.getSubmittedAt(), submission.getCompletedAt(),
                            "received", "Queued for compilation."));
        } catch (SubmissionService.DesignNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("Submission of design {} refused: {}", id, e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable UUID id) {
        String userId = authentication.getName();
        if (designs.findByIdAndUserId(id, userId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // The relational row cascades to the submissions; MongoDB has no foreign keys, so the
        // document is removed explicitly. Skipping it would leave an orphan topology that no
        // design points at, consuming space and readable by anyone who knows its id.
        designs.deleteById(id);
        canvases.deleteByDesignIdAndUserId(id.toString(), userId);
        return ResponseEntity.noContent().build();
    }

    private static Dtos.DesignSummary toSummary(DesignEntity design, long revision) {
        return new Dtos.DesignSummary(design.getId(), design.getName(), design.getDescription(),
                design.getScenario(), design.getNodeCount(), design.getEdgeCount(),
                revision, design.getCreatedAt(), design.getUpdatedAt());
    }

    /** A page size above this is a mistake or an attempt to pull the whole table. */
    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }
}
