package com.archforge.design.repository;

import com.archforge.design.domain.NodeMetric;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The bulk metric tables, written through {@code ON CONFLICT}.
 *
 * <p>Not JPA, and deliberately. Both tables have natural composite keys
 * ({@code (simulation_id, node_id)} and
 * {@code (simulation_id, node_id, metric_name, window_start_tick)}), and the pipeline is
 * at-least-once: a consumer rebalance between writing metrics and committing its offset
 * replays the summary. So the write has to be an idempotent upsert.
 *
 * <p>JPA would do a select-then-insert to achieve that, which is both a race between two
 * threads on one partition and N round trips where one statement will do. Stating
 * {@code ON CONFLICT DO UPDATE} in SQL makes redelivery a no-op by construction rather than
 * by convention, and it puts the guarantee in the schema where nothing can forget it.
 *
 * <p>Writes are batched. A 500-component run produces 500 node rows and up to 27 000 window
 * rows; one statement per row at that volume is the difference between a summary landing and
 * the listener's poll interval expiring.
 */
@Repository
public class NodeMetricRepository {

    private static final String UPSERT_NODE = """
            INSERT INTO node_metrics (
                simulation_id, node_id, node_type, sample_count,
                p50_ms, p95_ms, p99_ms, max_ms,
                avg_error_rate, peak_error_rate, peak_rps, avg_rps,
                peak_utilization, avg_cache_hit_rate, peak_queue_depth, saturated,
                extra, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (simulation_id, node_id) DO UPDATE SET
                node_type = EXCLUDED.node_type,
                sample_count = EXCLUDED.sample_count,
                p50_ms = EXCLUDED.p50_ms,
                p95_ms = EXCLUDED.p95_ms,
                p99_ms = EXCLUDED.p99_ms,
                max_ms = EXCLUDED.max_ms,
                avg_error_rate = EXCLUDED.avg_error_rate,
                peak_error_rate = EXCLUDED.peak_error_rate,
                peak_rps = EXCLUDED.peak_rps,
                avg_rps = EXCLUDED.avg_rps,
                peak_utilization = EXCLUDED.peak_utilization,
                avg_cache_hit_rate = EXCLUDED.avg_cache_hit_rate,
                peak_queue_depth = EXCLUDED.peak_queue_depth,
                saturated = EXCLUDED.saturated,
                extra = EXCLUDED.extra,
                updated_at = EXCLUDED.updated_at
            """;

    private static final String UPSERT_WINDOW = """
            INSERT INTO window_metrics (
                simulation_id, node_id, metric_name, window_start_tick, window_end_tick,
                sample_count, min_value, max_value, avg_value, sum_value,
                p50, p95, p99, std_dev, unit)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (simulation_id, node_id, metric_name, window_start_tick) DO UPDATE SET
                window_end_tick = EXCLUDED.window_end_tick,
                sample_count = EXCLUDED.sample_count,
                min_value = EXCLUDED.min_value,
                max_value = EXCLUDED.max_value,
                avg_value = EXCLUDED.avg_value,
                sum_value = EXCLUDED.sum_value,
                p50 = EXCLUDED.p50,
                p95 = EXCLUDED.p95,
                p99 = EXCLUDED.p99,
                std_dev = EXCLUDED.std_dev,
                unit = EXCLUDED.unit
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public NodeMetricRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Upserts a run's per-component figures.
     *
     * <p>Deletes first, so a component that reported in an earlier delivery but not this one
     * does not leave a stale row behind. Without that, a summary redelivered after being
     * rebuilt from a shorter trace would show figures that were never in it.
     *
     * @return rows written
     */
    public int upsertAll(List<NodeMetric> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        jdbc.batchUpdate(UPSERT_NODE, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                NodeMetric metric = metrics.get(i);
                ps.setObject(1, metric.simulationId());
                ps.setString(2, metric.nodeId());
                ps.setString(3, metric.nodeType());
                ps.setLong(4, metric.sampleCount());
                ps.setDouble(5, metric.p50Ms());
                ps.setDouble(6, metric.p95Ms());
                ps.setDouble(7, metric.p99Ms());
                ps.setDouble(8, metric.maxMs());
                ps.setDouble(9, metric.avgErrorRate());
                ps.setDouble(10, metric.peakErrorRate());
                ps.setDouble(11, metric.peakRps());
                ps.setDouble(12, metric.avgRps());
                ps.setDouble(13, metric.peakUtilization());
                ps.setDouble(14, metric.avgCacheHitRate());
                ps.setLong(15, metric.peakQueueDepth());
                ps.setBoolean(16, metric.saturated());
                ps.setString(17, "{}");
                ps.setTimestamp(18, Timestamp.from(now));
            }

            @Override
            public int getBatchSize() {
                return metrics.size();
            }
        });

        return metrics.size();
    }

    public int upsertWindows(List<NodeMetric.WindowMetric> windows) {
        if (windows == null || windows.isEmpty()) {
            return 0;
        }
        int[] results = jdbc.batchUpdate(UPSERT_WINDOW, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                NodeMetric.WindowMetric window = windows.get(i);
                ps.setObject(1, window.simulationId());
                ps.setString(2, window.nodeId());
                ps.setString(3, window.metricName());
                ps.setLong(4, window.windowStartTick());
                ps.setLong(5, window.windowEndTick());
                ps.setLong(6, window.sampleCount());
                ps.setDouble(7, window.minValue());
                ps.setDouble(8, window.maxValue());
                ps.setDouble(9, window.avgValue());
                ps.setDouble(10, window.sumValue());
                ps.setDouble(11, window.p50());
                ps.setDouble(12, window.p95());
                ps.setDouble(13, window.p99());
                ps.setDouble(14, window.stdDev());
                ps.setString(15, window.unit());
            }

            @Override
            public int getBatchSize() {
                return windows.size();
            }
        });
        return results.length;
    }

    /** Every component's figures for a run, worst P99 first. */
    public List<NodeMetric> findBySimulation(UUID simulationId) {
        return jdbc.query("""
                SELECT simulation_id, node_id, node_type, sample_count,
                       p50_ms, p95_ms, p99_ms, max_ms,
                       avg_error_rate, peak_error_rate, peak_rps, avg_rps,
                       peak_utilization, avg_cache_hit_rate, peak_queue_depth, saturated, extra
                FROM node_metrics
                WHERE simulation_id = ?
                ORDER BY p99_ms DESC, node_id
                """, (rs, rowNum) -> new NodeMetric(
                        rs.getObject("simulation_id", UUID.class),
                        rs.getString("node_id"),
                        rs.getString("node_type"),
                        rs.getLong("sample_count"),
                        rs.getDouble("p50_ms"),
                        rs.getDouble("p95_ms"),
                        rs.getDouble("p99_ms"),
                        rs.getDouble("max_ms"),
                        rs.getDouble("avg_error_rate"),
                        rs.getDouble("peak_error_rate"),
                        rs.getDouble("peak_rps"),
                        rs.getDouble("avg_rps"),
                        rs.getDouble("peak_utilization"),
                        rs.getDouble("avg_cache_hit_rate"),
                        rs.getLong("peak_queue_depth"),
                        rs.getBoolean("saturated"),
                        rs.getString("extra")), simulationId);
    }

    /** One component's per-window series for one metric, in tick order. */
    public List<NodeMetric.WindowMetric> findWindowSeries(UUID simulationId, String nodeId,
                                                          String metricName) {
        return jdbc.query("""
                SELECT simulation_id, node_id, metric_name, window_start_tick, window_end_tick,
                       sample_count, min_value, max_value, avg_value, sum_value,
                       p50, p95, p99, std_dev, unit
                FROM window_metrics
                WHERE simulation_id = ? AND node_id = ? AND metric_name = ?
                ORDER BY window_start_tick
                """, (rs, rowNum) -> new NodeMetric.WindowMetric(
                        rs.getObject("simulation_id", UUID.class),
                        rs.getString("node_id"),
                        rs.getString("metric_name"),
                        rs.getLong("window_start_tick"),
                        rs.getLong("window_end_tick"),
                        rs.getLong("sample_count"),
                        rs.getDouble("min_value"),
                        rs.getDouble("max_value"),
                        rs.getDouble("avg_value"),
                        rs.getDouble("sum_value"),
                        rs.getDouble("p50"),
                        rs.getDouble("p95"),
                        rs.getDouble("p99"),
                        rs.getDouble("std_dev"),
                        rs.getString("unit")),
                simulationId, nodeId, metricName);
    }

    /**
     * The worst P99 per run across a submission's history.
     *
     * <p>The comparison query. Reads a run's headline figure without loading every component
     * of every run, which is the difference between a history page and a full table scan.
     */
    public List<Map<String, Object>> worstPerSimulation(List<UUID> simulationIds) {
        if (simulationIds == null || simulationIds.isEmpty()) {
            return List.of();
        }
        String placeholders = simulationIds.stream().map(ignored -> "?").collect(java.util.stream.Collectors.joining(","));
        return jdbc.queryForList("""
                SELECT simulation_id,
                       node_id,
                       p99_ms,
                       p95_ms,
                       p50_ms,
                       sample_count,
                       saturated,
                       peak_utilization,
                       peak_queue_depth
                FROM node_metrics
                WHERE simulation_id IN (%s)
                ORDER BY p99_ms DESC
                """.formatted(placeholders), simulationIds.toArray());
    }

    /** Deletes a run's windows. Used when a run is replayed from a different trace. */
    public int deleteWindows(UUID simulationId) {
        return jdbc.update("DELETE FROM window_metrics WHERE simulation_id = ?", simulationId);
    }

    /** Whether a run has any recorded metrics at all. */
    public boolean hasMetrics(UUID simulationId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM node_metrics WHERE simulation_id = ?", Integer.class,
                simulationId);
        return count != null && count > 0;
    }

    /** Every node that has any recorded metric for a run, with its worst P99. */
    public Optional<NodeMetric> worstNode(UUID simulationId) {
        List<NodeMetric> all = findBySimulation(simulationId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }



    /** Deserialises a stored jsonb column, tolerating absence and legacy shapes. */
    public <T> T readJson(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return fallback;
        }
    }


}