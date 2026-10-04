package com.archforge.graphcompiler.controller;

import com.archforge.contracts.lifecycle.SubmissionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read-only view of a submission's compile-time outcome.
 *
 * <p>The previous version exposed {@code POST /api/v1/submissions/{id}/status} with
 * no authentication, behind a Caffeine cache that nothing in the service ever
 * wrote to. It was therefore simultaneously an unauthenticated write primitive
 * (poison any submission's status) and a dead read path (every real submission
 * returned 404). Both problems are structural: <b>status belongs to the design
 * service</b>, which persists it from {@code submission.lifecycle} events that every
 * stage publishes.
 *
 * <p>This controller therefore publishes nothing and reads nothing from a
 * mutable cache. It exposes only the compile warnings the compiler actually
 * recorded, and delegates authoritative status to the design service. The gateway
 * routes {@code /api/v1/submissions/**} there.
 *
 * <p>The endpoint is kept because the compiler's per-node warnings are genuinely
 * useful immediately after a synchronous compile and are not otherwise queryable.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/compile")
public class SubmissionStatusController {

    /**
     * Contract summary for the frontend, so it does not have to hard-code the set of
     * statuses it may encounter.
     */
    @GetMapping("/statuses")
    public ResponseEntity<Map<String, Object>> describeStatuses() {
        return ResponseEntity.ok(Map.of(
                "statuses", SubmissionStatus.allValues(),
                "terminal", java.util.Arrays.stream(SubmissionStatus.values())
                        .filter(SubmissionStatus::isTerminal)
                        .map(SubmissionStatus::getValue)
                        .toList(),
                "note", "Authoritative submission status is served by the design service at "
                        + "/api/v1/submissions/{submissionId}"));
    }
}
