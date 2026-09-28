package com.archforge.telemetry.streams;

import com.archforge.telemetry.model.AggregatedMetrics;
import com.archforge.telemetry.model.TelemetryEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.WindowStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.time.Duration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class TelemetryStreamsTopology {

    private final ObjectMapper objectMapper;

    @Value("${telemetry.kafka.topics.simulation-telemetry:simulation.telemetry}")
    private String telemetryTopic;

    @Value("${telemetry.kafka.topics.aggregated-metrics:aggregated.metrics}")
    private String aggregatedMetricsTopic;

    @Value("${telemetry.aggregation.window-size-seconds:10}")
    private int windowSizeSeconds;

    @Value("${telemetry.aggregation.window-grace-seconds:5}")
    private int windowGraceSeconds;

    @Bean
    public KStream<String, String> telemetryStream(StreamsBuilder streamsBuilder) {
        KStream<String, String> source = streamsBuilder.stream(telemetryTopic,
                Consumed.with(Serdes.String(), Serdes.String()));

        KStream<String, TelemetryEvent> events = source
                .mapValues(this::deserializeEvent)
                .filter((key, value) -> value != null);

        KGroupedStream<String, TelemetryEvent> grouped = events
                .groupBy((key, value) -> value.getSimulationId() + ":" + value.getNodeId() + ":" + value.getMetricName(),
                        Grouped.with(Serdes.String(), new JsonSerde<>(TelemetryEvent.class)));

        KStream<String, String> aggregated = grouped
                .windowedBy(TimeWindows.ofSizeAndGrace(
                        Duration.ofSeconds(windowSizeSeconds),
                        Duration.ofSeconds(windowGraceSeconds)))
                .aggregate(
                        Aggregation::new,
                        (key, event, aggregation) -> aggregation.add(event),
                        Materialized.<String, Aggregation, WindowStore<org.apache.kafka.common.utils.Bytes, byte[]>>as("telemetry-aggregates")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(new AggregationSerde())
                )
                .toStream()
                .map((windowedKey, aggregation) -> {
                    String[] parts = windowedKey.key().split(":");
                    String simulationId = parts[0];
                    String nodeId = parts[1];
                    String metricName = parts[2];

                    AggregatedMetrics metrics = aggregation.toMetrics(simulationId, nodeId, metricName,
                            windowedKey.window().startTime().toEpochMilli(),
                            windowedKey.window().endTime().toEpochMilli());

                    try {
                        return KeyValue.pair(simulationId + ":" + nodeId, objectMapper.writeValueAsString(metrics));
                    } catch (Exception e) {
                        log.error("Failed to serialize aggregated metrics", e);
                        return null;
                    }
                })
                .filter((key, value) -> value != null);

        aggregated.to(aggregatedMetricsTopic, Produced.with(Serdes.String(), Serdes.String()));

        log.info("Kafka Streams topology configured with {}s windows", windowSizeSeconds);

        return source;
    }

    private TelemetryEvent deserializeEvent(String json) {
        try {
            return objectMapper.readValue(json, TelemetryEvent.class);
        } catch (Exception e) {
            log.error("Failed to deserialize telemetry event", e);
            return null;
        }
    }

    @Getter
    public static class Aggregation {
        double min;
        double max;
        double sum;
        long count;
        String unit;

        public Aggregation() {
            this.min = Double.MAX_VALUE;
            this.max = Double.MIN_VALUE;
            this.sum = 0;
            this.count = 0;
            this.unit = "";
        }

        public Aggregation add(TelemetryEvent event) {
            double value = event.getValue();
            this.min = Math.min(this.min, value);
            this.max = Math.max(this.max, value);
            this.sum += value;
            this.count++;
            this.unit = event.getUnit();
            return this;
        }

        public AggregatedMetrics toMetrics(String simulationId, String nodeId, String metricName,
                                            long windowStart, long windowEnd) {
            return AggregatedMetrics.builder()
                    .simulationId(simulationId)
                    .nodeId(nodeId)
                    .metricName(metricName)
                    .min(this.min == Double.MAX_VALUE ? 0 : this.min)
                    .max(this.max == Double.MIN_VALUE ? 0 : this.max)
                    .avg(this.count > 0 ? this.sum / this.count : 0)
                    .count(this.count)
                    .windowStart(java.time.Instant.ofEpochMilli(windowStart))
                    .windowEnd(java.time.Instant.ofEpochMilli(windowEnd))
                    .unit(this.unit)
                    .build();
        }
    }
}
