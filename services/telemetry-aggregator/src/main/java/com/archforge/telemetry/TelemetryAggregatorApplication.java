package com.archforge.telemetry;

import com.archforge.telemetry.config.AggregatorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Telemetry aggregator: windows one simulation's samples and publishes the aggregates the
 * rest of the pipeline reads.
 *
 * <p>{@link EnableScheduling} is what drains terminal events whose samples never arrived,
 * and {@link EnableConfigurationProperties} is what binds the window size — without the
 * latter the aggregator runs on the class defaults regardless of what
 * {@code application.yml} says, which is the same "two sources of truth, and the YAML
 * changes nothing" defect the engine's own properties class was written to fix.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AggregatorProperties.class)
public class TelemetryAggregatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(TelemetryAggregatorApplication.class, args);
    }
}