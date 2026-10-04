package com.archforge.graphcompiler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Graph Compiler Service.
 *
 * <p>Compiles a React Flow canvas topology into an executable DAG and publishes it to
 * {@code simulation.tasks}.
 *
 * <p>{@code com.archforge.messaging} is added to the component scan so the shared Kafka
 * factories and lifecycle publisher are registered. Importing the package by name rather
 * than relying on the default {@code com.archforge} root keeps the scan explicit: the
 * contracts module holds wire types only and must never be scanned for beans.
 */
@SpringBootApplication(scanBasePackages = {
        "com.archforge.graphcompiler",
        "com.archforge.messaging"
})
@EnableScheduling
public class GraphCompilerApplication {

    public static void main(String[] args) {
        SpringApplication.run(GraphCompilerApplication.class, args);
    }
}
