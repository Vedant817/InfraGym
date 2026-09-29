package com.archforge.graphcompiler.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/submissions")
@RequiredArgsConstructor
public class SubmissionStatusController {

    private final Cache<String, SubmissionStatus> statusCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build();

    @GetMapping("/{submissionId}")
    public ResponseEntity<SubmissionStatus> getSubmissionStatus(@PathVariable String submissionId) {
        SubmissionStatus status = statusCache.getIfPresent(submissionId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    @PostMapping("/{submissionId}/status")
    public ResponseEntity<Void> updateSubmissionStatus(@PathVariable String submissionId,
                                                        @RequestBody SubmissionStatus status) {
        statusCache.put(submissionId, status);
        return ResponseEntity.ok().build();
    }

    @lombok.Value
    @lombok.Builder
    public static class SubmissionStatus {
        String submissionId;
        String status;
        String simulationId;
        String error;
        long timestamp;
    }
}
