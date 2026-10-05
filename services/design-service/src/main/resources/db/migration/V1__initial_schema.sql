-- Schema for the system of record.
--
-- Two ideas shape this:
--
-- 1. The event log is the truth and the rows are a projection. submission_events is
--    append-only and keyed by the producer's event id, so a redelivered lifecycle event
--    is a no-op rather than a second entry -- the pipeline is at-least-once by
--    construction, and an audit trail that double-counts a redelivery is not an audit
--    trail. submissions.status is then derived from the events that have been applied.
--
-- 2. Percentiles are stored as columns, not JSON. p50/p95/p99 are what every chart and
--    every finding sorts by, so they are queryable, indexable and comparable in SQL. The
--    rest of a node's figures go in jsonb where nothing has to read them by name.
--
-- The node_metrics and evaluation_findings uniqueness is on (simulation_id, node_id) and
-- (evaluation_id, position) rather than a generated key, so a redelivered event that
-- upserts produces the same row instead of a duplicate that would double every aggregate
-- computed from this table.

CREATE TABLE designs (
    id              UUID PRIMARY KEY,
    user_id         VARCHAR(128) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    scenario        VARCHAR(64)  NOT NULL DEFAULT 'normal',
    -- The node/edge counts are denormalised so a design list does not have to load every
    -- topology to render a row. They are written with the design and are a cache of the
    -- Mongo document, not a second source of truth.
    node_count      INTEGER      NOT NULL DEFAULT 0,
    edge_count      INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_designs_user ON designs (user_id, updated_at DESC);

COMMENT ON TABLE designs IS
    'A design the user drew. The topology itself lives in MongoDB (canvas_documents); this '
    'table holds what a list needs without loading a document per row.';

CREATE TABLE submissions (
    id              UUID PRIMARY KEY,
    design_id       UUID NOT NULL REFERENCES designs (id) ON DELETE CASCADE,
    user_id         VARCHAR(128) NOT NULL,
    simulation_id   UUID,
    correlation_id  UUID,
    status          VARCHAR(32)  NOT NULL,
    scenario        VARCHAR(64)  NOT NULL,
    scenario_notes  TEXT,
    -- The submitted topology, frozen. A design can be edited after submission, so the
    -- submission holds its own copy: re-reading the design to explain a run that already
    -- happened would describe a design that was not the one that was measured.
    topology        JSONB        NOT NULL,
    submitted_at    TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_submissions_user ON submissions (user_id, submitted_at DESC);
CREATE INDEX idx_submissions_design ON submissions (design_id, submitted_at DESC);
-- The simulation lookup happens on every lifecycle event, which can arrive before the
-- submission row exists in a reader's mental model; indexed because it is the hot path.
CREATE INDEX idx_submissions_simulation ON submissions (simulation_id);

CREATE TABLE submission_events (
    event_id        UUID PRIMARY KEY,
    submission_id   UUID NOT NULL,
    simulation_id   UUID,
    correlation_id  UUID,
    stage           VARCHAR(64)  NOT NULL,
    status          VARCHAR(32),
    message         TEXT,
    details         JSONB,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The read path is "everything that happened to this submission, in order".
CREATE INDEX idx_submission_events_submission
    ON submission_events (submission_id, occurred_at, event_id);
CREATE INDEX idx_submission_events_simulation ON submission_events (simulation_id);

COMMENT ON TABLE submission_events IS
    'Append-only. event_id is the producer''s id and the primary key, so a redelivered '
    'event is rejected by the database rather than by application logic that can be skipped.';

CREATE TABLE simulations (
    id              UUID PRIMARY KEY,
    submission_id   UUID NOT NULL REFERENCES submissions (id) ON DELETE CASCADE,
    scenario        VARCHAR(64)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    tick_count      BIGINT       NOT NULL DEFAULT 0,
    total_ticks     BIGINT       NOT NULL DEFAULT 0,
    tick_millis     BIGINT       NOT NULL DEFAULT 0,
    warnings        JSONB,
    -- The engine's accounting identity, stored as published. Kept as columns so
    -- "offered == served + failed + queued" can be asserted in SQL over history, which is
    -- the only way a systematic miscount is ever going to be noticed.
    offered         DOUBLE PRECISION NOT NULL DEFAULT 0,
    served          DOUBLE PRECISION NOT NULL DEFAULT 0,
    failed          DOUBLE PRECISION NOT NULL DEFAULT 0,
    queued          DOUBLE PRECISION NOT NULL DEFAULT 0,
    graph_metrics   JSONB,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    recorded_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_simulations_submission ON simulations (submission_id);

CREATE TABLE node_metrics (
    simulation_id   UUID NOT NULL REFERENCES simulations (id) ON DELETE CASCADE,
    node_id         VARCHAR(128) NOT NULL,
    node_type       VARCHAR(64)  NOT NULL,
    sample_count    BIGINT       NOT NULL DEFAULT 0,
    p50_ms          DOUBLE PRECISION NOT NULL DEFAULT 0,
    p95_ms          DOUBLE PRECISION NOT NULL DEFAULT 0,
    p99_ms          DOUBLE PRECISION NOT NULL DEFAULT 0,
    max_ms          DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_error_rate  DOUBLE PRECISION NOT NULL DEFAULT 0,
    peak_error_rate DOUBLE PRECISION NOT NULL DEFAULT 0,
    peak_rps        DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_rps         DOUBLE PRECISION NOT NULL DEFAULT 0,
    peak_utilization DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_cache_hit_rate DOUBLE PRECISION NOT NULL DEFAULT -1,
    peak_queue_depth BIGINT       NOT NULL DEFAULT 0,
    saturated       BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Everything else the aggregator measured, for a reader that wants to plot it
    -- without a migration per metric.
    extra           JSONB,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (simulation_id, node_id)
);

-- The comparison query: worst p99 first, across every run of a submission.
CREATE INDEX idx_node_metrics_p99 ON node_metrics (p99_ms DESC);

CREATE TABLE window_metrics (
    simulation_id   UUID NOT NULL REFERENCES simulations (id) ON DELETE CASCADE,
    node_id         VARCHAR(128) NOT NULL,
    metric_name     VARCHAR(64)  NOT NULL,
    window_start_tick BIGINT     NOT NULL,
    window_end_tick BIGINT       NOT NULL,
    sample_count    BIGINT       NOT NULL DEFAULT 0,
    min_value       DOUBLE PRECISION NOT NULL DEFAULT 0,
    max_value       DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_value       DOUBLE PRECISION NOT NULL DEFAULT 0,
    sum_value       DOUBLE PRECISION NOT NULL DEFAULT 0,
    p50             DOUBLE PRECISION NOT NULL DEFAULT 0,
    p95             DOUBLE PRECISION NOT NULL DEFAULT 0,
    p99             DOUBLE PRECISION NOT NULL DEFAULT 0,
    std_dev         DOUBLE PRECISION NOT NULL DEFAULT 0,
    unit            VARCHAR(32),
    PRIMARY KEY (simulation_id, node_id, metric_name, window_start_tick)
);

-- A chart asks for one component's series in tick order.
CREATE INDEX idx_window_metrics_series
    ON window_metrics (simulation_id, node_id, metric_name, window_start_tick);

CREATE TABLE evaluations (
    id              UUID PRIMARY KEY,
    submission_id   UUID NOT NULL REFERENCES submissions (id) ON DELETE CASCADE,
    simulation_id   UUID,
    overall_score   INTEGER      NOT NULL,
    verdict         VARCHAR(64)  NOT NULL,
    summary         TEXT,
    strengths       JSONB,
    weaknesses      JSONB,
    recommendations JSONB,
    bottlenecks     JSONB,
    references_doc  JSONB,
    warnings        JSONB,
    model           VARCHAR(128),
    latency_ms      BIGINT       NOT NULL DEFAULT 0,
    prompt_tokens   INTEGER      NOT NULL DEFAULT 0,
    completion_tokens INTEGER    NOT NULL DEFAULT 0,
    -- Whether the evaluation is the full arithmetic assessment or the arithmetic plus a
    -- model's narrative. Stored rather than inferred, because a reader shown a score must
    -- be able to see whether anything was missing from it.
    degraded        BOOLEAN      NOT NULL DEFAULT FALSE,
    degraded_reason TEXT,
    evaluated_at    TIMESTAMPTZ  NOT NULL,
    recorded_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_evaluations_submission ON evaluations (submission_id, evaluated_at DESC);

CREATE TABLE evaluation_findings (
    evaluation_id   UUID NOT NULL REFERENCES evaluations (id) ON DELETE CASCADE,
    position        INTEGER NOT NULL,
    node_id         VARCHAR(128),
    severity        VARCHAR(16)  NOT NULL,
    category        VARCHAR(64)  NOT NULL,
    title           TEXT         NOT NULL,
    detail          TEXT,
    evidence        TEXT,
    recommendation  TEXT,
    related_node_ids JSONB,
    PRIMARY KEY (evaluation_id, position)
);

CREATE INDEX idx_evaluation_findings_node ON evaluation_findings (node_id);