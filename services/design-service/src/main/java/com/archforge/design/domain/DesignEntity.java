package com.archforge.design.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A design the user drew.
 *
 * <p>Holds only what a list needs. The topology itself is a MongoDB document: it is bulky,
 * schemaless, rewritten on every canvas edit, and read whole or not at all -- which is
 * precisely the shape of data a relational table is bad at and a document store is good at.
 * Splitting it this way means a user's design list is one indexed query rather than one
 * document load per row.
 *
 * <p>{@code nodeCount} and {@code edgeCount} are a cache of the document, written
 * alongside it. They exist so a list can render "6 nodes, 6 edges" without a Mongo round
 * trip per row, and they are never the source of truth for a topology.
 */
@Entity
@Table(name = "designs", indexes = {
        @Index(name = "idx_designs_user", columnList = "userId, updatedAt")
})
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class DesignEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 64)
    private String scenario = "normal";

    @Column(name = "node_count", nullable = false)
    private int nodeCount;

    @Column(name = "edge_count", nullable = false)
    private int edgeCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private DesignEntity(UUID id, String userId, String name, String description,
                         String scenario, int nodeCount, int edgeCount, Instant createdAt) {
        this.id = id == null ? UUID.randomUUID() : id;
        this.userId = userId;
        this.name = name;
        this.description = description;
        this.scenario = scenario == null || scenario.isBlank() ? "normal" : scenario;
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = this.createdAt;
    }

    public static DesignEntity create(String userId, String name, String description,
                                      String scenario, int nodeCount, int edgeCount) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("A design must have an owner");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A design must have a name");
        }
        return new DesignEntity(UUID.randomUUID(), userId, name.trim(), description,
                scenario, nodeCount, edgeCount, Instant.now());
    }

    /** The document counts are a cache of the topology, so they move with it. */
    public void topologyChanged(int nodeCount, int edgeCount) {
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
    }

    public void renamed(String name, String description) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
        if (description != null) {
            this.description = description;
        }
    }

    @PrePersist
    void onInsert() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Whether this design belongs to {@code candidateUser}. */
    public boolean ownedBy(String candidateUser) {
        return userId != null && userId.equals(candidateUser);
    }
}