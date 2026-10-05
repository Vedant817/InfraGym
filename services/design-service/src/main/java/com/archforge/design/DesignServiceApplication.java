package com.archforge.design;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The system of record.
 *
 * <p>Owns three things the rest of the platform cannot be understood without: what a user
 * drew, what happened when it was run, and what the measurements said. It is also the
 * pipeline's only entry point -- {@code DesignSubmittedEvent} names the gateway as its
 * producer, and while nothing produced it a design submitted through the product could never
 * reach the compiler.
 *
 * <p>Data lives where its shape suits it: designs, submissions, the event log, simulations
 * and metrics in PostgreSQL, because everything there is queried by field and nothing is
 * rewritten wholesale; topologies in MongoDB, because a canvas is edited as a whole document
 * and read as one.
 */
@SpringBootApplication
public class DesignServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DesignServiceApplication.class, args);
    }
}