package com.archforge.simulation;

import com.archforge.simulation.config.SimulationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Simulation engine entry point.
 *
 * <p>Consumes compiled DAGs from {@code simulation.tasks}, runs a deterministic
 * queueing simulation of each, publishes per-tick telemetry to
 * {@code simulation.telemetry} and lifecycle transitions to
 * {@code simulation.lifecycle}.
 *
 * <p>Deliberately no HTTP ingestion: a design enters the pipeline through the gateway.
 * The web layer here is actuator plus a read-only view of recent runs.
 */
@SpringBootApplication
@EnableConfigurationProperties(SimulationProperties.class)
public class SimulationEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulationEngineApplication.class, args);
    }
}
