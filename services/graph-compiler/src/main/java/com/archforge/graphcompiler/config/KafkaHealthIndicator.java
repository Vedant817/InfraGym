package com.archforge.graphcompiler.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaHealthIndicator implements HealthIndicator {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public Health health() {
        try {
            kafkaTemplate.execute(template -> {
                log.debug("Kafka health check: broker connection OK");
                return null;
            });
            return Health.up()
                    .withDetail("broker", "connected")
                    .build();
        } catch (Exception e) {
            log.error("Kafka health check failed", e);
            return Health.down()
                    .withDetail("broker", "disconnected")
                    .withException(e)
                    .build();
        }
    }
}
