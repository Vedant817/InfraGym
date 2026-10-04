package com.archforge.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Applies {@link com.archforge.contracts.json.ContractJson} rules to every
 * Spring-managed {@code ObjectMapper}.
 *
 * <p>Without this, a service's own {@code spring.jackson.*} settings decide how
 * an {@code Instant} is written on the wire, and the receiving service — using a
 * different configuration — reads it as the wrong type or fails. That mismatch is
 * invisible to any test that only serialises, and fatal to any test that only
 * deserialises.
 *
 * <p>The critical rule is {@code WRITE_DATES_AS_TIMESTAMPS = false}: an
 * {@code Instant} must be an ISO-8601 string, because (a) a double cannot hold
 * nanoseconds at present-day epochs, and (b) Kafka payloads get read by humans
 * during an incident.
 */
@Configuration(proxyBeanMethods = false)
public class ContractJacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer contractJacksonCustomizer() {
        return builder -> builder
                .modulesToInstall(new JavaTimeModule())
                .featuresToDisable(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE,
                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                        SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .serializationInclusion(JsonInclude.Include.NON_NULL);
    }
}
