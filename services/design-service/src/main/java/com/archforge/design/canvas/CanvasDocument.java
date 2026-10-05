package com.archforge.design.canvas;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A design's topology, as a document.
 *
 * <p>MongoDB rather than PostgreSQL, and the choice is about write shape. A topology is
 * rewritten whole on every canvas edit, read whole or not at all, and has no fixed set of
 * queryable fields beyond the owner's id and the timestamps. Putting it in the relational
 * schema would mean either a row per node rewritten on every keystroke, or one row with a
 * jsonb column that Postgres stores and nobody queries -- and either way a design list would
 * load a document per row.
 *
 * <p>Indexed on {@code userId} because that is the query the design list makes, and because
 * the index is also the thing that makes an ownership check cheap enough to always run.
 */
@Document(collection = "canvas_documents")
public class CanvasDocument {

    @Id
    private String id;

    @Indexed
    private String designId;

    @Indexed
    private String userId;

    /**
     * The topology as the user drew it.
     *
     * <p>Stored as a nested structure rather than a string, so a query can ask "which designs
     * contain a database" without parsing JSON in the application.
     */
    private TopologySnapshot topology;

    /** Monotonic per design. Lets a reader ask for "everything after revision N". */
    private long revision;

    private Instant createdAt;

    private Instant updatedAt;

    public CanvasDocument() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDesignId() {
        return designId;
    }

    public void setDesignId(String designId) {
        this.designId = designId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public TopologySnapshot getTopology() {
        return topology;
    }

    public void setTopology(TopologySnapshot topology) {
        this.topology = topology;
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}