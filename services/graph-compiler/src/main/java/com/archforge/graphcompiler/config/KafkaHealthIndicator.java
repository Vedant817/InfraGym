package com.archforge.graphcompiler.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaHealthIndicator implements HealthIndicator {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public Health health() {
        try {
            CompletableFuture<String> future = kafkaTemplate.send("health-check", "ping")
                    .thenApply(result -> "pong");

            String result = future.get(5, TimeUnit.SECONDS);

            if ("pong".equals(result)) {
                return Health.up()
                        .withDetail("broker", "connected")
                        .build();
            } else {
                return Health.down()
                        .withDetail("broker", "unexpected response")
                        .build();
            }
        } catch (TimeoutException e) {
            log.error("Kafka health check timed out", e);
            return Health.down()
                    .withDetail("broker", "timeout")
                    .build();
        } catch (Exception e) {
            log.error("Kafka health check failed", e);
            return Health.down()
                    .withDetail("broker", "disconnected")
                    .build();
        }
    }
}
