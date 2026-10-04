package com.archforge.simulation.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Reports broker reachability to the readiness probe.
 *
 * <p>{@code AdminClient.describeCluster} is a metadata operation: it creates no topic,
 * proves both the produce and the consume path are configured, and answers inside the
 * probe's own timeout.
 *
 * <p>The alternative the previous version used — publishing to a topic literally named
 * {@code health-check} on every scrape and blocking up to five seconds for it — had
 * three problems: the topic was declared nowhere (so with auto-creation off, which this
 * stack sets deliberately, every scrape failed); {@code /actuator/health} is
 * unauthenticated, so a request loop could pin the worker pool for five seconds per
 * call; and it only proved the producer path.
 */
@Slf4j
@Component("kafka")
@ConditionalOnProperty(name = "archforge.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaHealthIndicator implements HealthIndicator {

    private static final long PROBE_TIMEOUT_SECONDS = 5;

    private final String bootstrapServers;
    private final MeterRegistry meterRegistry;

    public KafkaHealthIndicator(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                                MeterRegistry meterRegistry) {
        this.bootstrapServers = bootstrapServers;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Health health() {
        int timeoutMillis = (int) TimeUnit.SECONDS.toMillis(PROBE_TIMEOUT_SECONDS);

        Properties props = new Properties();
        props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(CommonClientConfigs.REQUEST_TIMEOUT_MS_CONFIG, timeoutMillis);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMillis);

        try (AdminClient admin = AdminClient.create(props)) {
            var cluster = admin.describeCluster();
            int nodeCount = cluster.nodes().get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS).size();
            String clusterId = cluster.clusterId().get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int topicCount = admin.listTopics().names()
                    .get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS).size();

            meterRegistry.counter("kafka.health.success").increment();
            return Health.up()
                    .withDetail("clusterId", clusterId)
                    .withDetail("brokers", nodeCount)
                    .withDetail("topics", topicCount)
                    .withDetail("bootstrapServers", bootstrapServers)
                    .build();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            meterRegistry.counter("kafka.health.failed").increment();
            return Health.down().withDetail("error", "probe interrupted").build();
        } catch (Exception e) {
            meterRegistry.counter("kafka.health.failed").increment();
            log.debug("Kafka health probe failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("error", e.getClass().getSimpleName())
                    .withDetail("message", e.getMessage() == null ? "unavailable" : e.getMessage())
                    .withDetail("bootstrapServers", bootstrapServers)
                    .build();
        }
    }
}
