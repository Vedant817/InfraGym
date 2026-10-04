package com.archforge.contracts.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;

/**
 * The one Jackson configuration used to read and write every contract payload.
 *
 * <p>Serialisation happens in one service and deserialisation in another, so a
 * mapper difference is a production incident that unit tests in the producing
 * service cannot see. Two rules follow, and both are enforced here:
 *
 * <ol>
 *   <li><b>Unknown properties are ignored.</b> Consumers must tolerate fields
 *       added by a newer producer. The old design set
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES} to its default in one service and
 *       disabled it in another, so a schema addition broke exactly the service
 *       nobody tested.</li>
 *   <li><b>{@code Instant} is always ISO-8601, never epoch millis.</b> Kafka
 *       payloads are read by humans during incidents; {@code 1732...} in a log
 *       line is not something anyone can act on.</li>
 * </ol>
 */
public final class ContractJson {

    private ContractJson() {
    }

    /** A fresh, correctly configured mapper. Prefer this over {@code new ObjectMapper()}. */
    public static ObjectMapper create() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new ParameterNamesModule())
                // Additive schema evolution must not break a deployed consumer.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                // ISO-8601 strings on the wire, not epoch millis: Kafka payloads get
                // read by humans during an incident.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }
}
