package com.archforge.design.canvas;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Canvas documents.
 *
 * <p>Every read is owner-filtered, including the one by design id. That is the difference
 * between a document store and a shared bucket: an id in the URL is a bearer reference, and
 * a design's topology is exactly the thing a user did not intend to publish.
 */
@Repository
public class CanvasRepository {

    private final MongoTemplate mongo;

    public CanvasRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public CanvasDocument save(CanvasDocument document) {
        return mongo.save(document);
    }

    public Optional<CanvasDocument> findByDesignIdAndUserId(String designId, String userId) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("designId").is(designId).and("userId").is(userId)),
                CanvasDocument.class));
    }

    /**
     * Every design's latest topology for an owner, for the design list.
     *
     * <p>One query for all of them rather than one per design. A list of 50 designs that loads
     * a document per row is 50 round trips on the path the user takes every time they open
     * the app.
     */
    public List<CanvasDocument> findByUserId(String userId) {
        return mongo.find(Query.query(Criteria.where("userId").is(userId)), CanvasDocument.class);
    }

    /** Inserts the initial revision; creation never overwrites an existing document. */
    public CanvasDocument create(String designId, String userId, TopologySnapshot topology) {
        Instant now = Instant.now();
        CanvasDocument document = new CanvasDocument();
        document.setId(designId);
        document.setDesignId(designId);
        document.setUserId(userId);
        document.setTopology(topology);
        document.setRevision(1);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        return mongo.insert(document);
    }

    /**
     * MongoDB compares and increments in one document operation. No upsert: a stale
     * revision or missing/foreign document must never create or overwrite a topology.
     * The returned document is this write's post-image, not a later editor's revision.
     */
    public Optional<CanvasDocument> compareAndSetRevision(String designId, String userId,
                                                         long expectedRevision,
                                                         TopologySnapshot topology) {
        Update update = new Update()
                .set("topology", topology)
                .set("updatedAt", Instant.now())
                .inc("revision", 1);
        Query query = Query.query(Criteria.where("designId").is(designId)
                .and("userId").is(userId).and("revision").is(expectedRevision));
        return Optional.ofNullable(mongo.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), CanvasDocument.class));
    }

    public long countByUserId(String userId) {
        return mongo.count(Query.query(Criteria.where("userId").is(userId)),
                CanvasDocument.class);
    }

    public void deleteByDesignIdAndUserId(String designId, String userId) {
        mongo.remove(Query.query(Criteria.where("designId").is(designId).and("userId").is(userId)),
                CanvasDocument.class);
    }
}
