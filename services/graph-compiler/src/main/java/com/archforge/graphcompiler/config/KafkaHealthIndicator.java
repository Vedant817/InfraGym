package com.archforge.graphcompiler.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Reports broker reachability to the readiness probe.
 *
 * <p>The previous implementation sent a real message to a topic literally named
 * {@code health-check} on every scrape and then blocked the request thread for up to
 * five seconds waiting for it. Three problems with that:
 * <ul>
 *   <li>The topic is declared nowhere. With auto-creation on it would be created and
 *       filled by health checks; with it off — which this stack sets deliberately —
 *       every scrape failed.</li>
 *   <li>{@code /actuator/health} is unauthenticated, so a request loop could pin the
 *       Tomcat worker pool for five seconds per call.</li>
 *   <li>It only proved the <em>producer</em> path. A broker can accept a produce
 *       request while a consumer group's coordinator is unavailable.</li>
 * </ul>
 *
 * <p>{@code AdminClient.describeCluster} is the correct check: it is a metadata
 * operation, creates no topic, and the short timeout bounds the probe itself. It is
 * also confined to the health-indicator thread pool rather than the request thread.
 */
@Slf4j
@Component("kafka")
@ConditionalOnProperty(name = "archforge.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaHealthIndicator implements HealthIndicator {

    private static final long PROBE_TIMEOUT_SECONDS = 5;

    private final String bootstrapServers;
    private final int expectedTopicCount;
    private final MeterRegistry meterRegistry;

    public KafkaHealthIndicator(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                                @Value("${archforge.kafka.health.expected-topics:3}") int expectedTopicCount,
                                MeterRegistry meterRegistry) {
        this.bootstrapServers = bootstrapServers;
        this.expectedTopicCount = expectedTopicCount;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Health health() {
        int timeoutMillis = (int) TimeUnit.SECONDS.toMillis(PROBE_TIMEOUT_SECONDS);

        Properties props = new Properties();
        props.put(org.apache.kafka.clients.CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(org.apache.kafka.clients.CommonClientConfigs.REQUEST_TIMEOUT_MS_CONFIG, timeoutMillis);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMillis);

        try (AdminClient admin = AdminClient.create(props)) {
            var cluster = admin.describeCluster();
            int nodeCount = cluster.nodes().get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS).size();
            String clusterId = cluster.clusterId().get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ListTopicsResult topics = admin.listTopics();

            int topicCount = topics.names().get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS).size();
            meterRegistry.counter("kafka.health.success").increment();

            Health.Builder builder = Health.up()
                    .withDetail("clusterId", clusterId)
                    .withDetail("brokers", nodeCount)
                    .withDetail("topics", topicCount)
                    .withDetail("bootstrapServers", bootstrapServers);

            // Report the shortfall rather than failing: a broker being up is what the
            // probe is for, and a missing topic is a deployment-ordering problem that
            // a hard DOWN would turn into a crash-loop while the bootstrapper finishes.
            if (topicCount < expectedTopicCount) {
                builder.withDetail("warning",
                        "fewer topics than expected; has the topic bootstrapper run?");
            }
            return builder.build();

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
