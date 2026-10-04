package com.archforge.graphcompiler.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.TimeUnit;

/**
 * Consumer-lag metrics for this service's listener.
 *
 * <p>Lag is the only early warning that the pipeline is falling behind. If
 * {@code design.submissions} accumulates while this service reports healthy, a
 * submission has been sitting unsubmitted with nothing in the logs to say so — which is
 * exactly the failure the previous design could not detect, because it published no
 * lag metric and its health check wrote to a topic that did not exist.
 *
 * <h2>Why partitions come from metadata, not from committed offsets</h2>
 * {@code listConsumerGroupOffsets} returns nothing until the group has committed at
 * least one record, so a poll that used it as the partition source published <em>no
 * gauge at all</em> on a freshly started service — an absent metric is
 * indistinguishable from a broken exporter. Partition assignments are therefore read
 * from topic metadata, and a partition with no committed offset is treated as
 * offset 0, which is exactly its true lag. The consumer group id is still reported as
 * a tag so the figure is attributable.
 *
 * <p>The admin client is created per poll and closed immediately: a long-lived one holds
 * a broker connection open and caches metadata that goes stale.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "archforge.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaMetricsConfig {

    private static final long ADMIN_TIMEOUT_SECONDS = 10;

    private final MeterRegistry meterRegistry;
    private final String bootstrapServers;
    private final String groupId;
    private final List<String> topics;

    public KafkaMetricsConfig(MeterRegistry meterRegistry,
                              @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                              @Value("${spring.kafka.consumer.group-id}") String groupId,
                              @Value("${archforge.topics.design-submissions:design.submissions}") String submissionsTopic) {
        this.meterRegistry = meterRegistry;
        this.bootstrapServers = bootstrapServers;
        this.groupId = groupId;
        this.topics = List.of(submissionsTopic);
    }

    @Scheduled(fixedDelayString = "${archforge.kafka.metrics.poll-interval-ms:30000}",
            initialDelayString = "${archforge.kafka.metrics.initial-delay-ms:15000}")
    public void publishLagMetrics() {
        Map<String, Object> props = new HashMap<>();
        props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(CommonClientConfigs.REQUEST_TIMEOUT_MS_CONFIG, (int) TimeUnit.SECONDS.toMillis(ADMIN_TIMEOUT_SECONDS));

        try (AdminClient admin = AdminClient.create(props)) {
            List<TopicPartition> partitions = discoverPartitions(admin);
            if (partitions.isEmpty()) {
                meterRegistry.counter("graph.kafka.metrics.poll.empty").increment();
                log.debug("No partitions found for topics {}; skipping lag report", topics);
                return;
            }

            Map<TopicPartition, Long> committed = fetchCommittedOffsets(admin);
            Map<TopicPartition, OffsetSpec> request = new HashMap<>();
            for (TopicPartition partition : partitions) {
                request.put(partition, OffsetSpec.latest());
            }
            // all() returns one entry per requested partition.
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets =
                    admin.listOffsets(request).all().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            long totalLag = 0;
            int reporting = 0;
            for (TopicPartition partition : partitions) {
                ListOffsetsResult.ListOffsetsResultInfo end = endOffsets.get(partition);
                if (end == null) {
                    continue;
                }
                Long offset = committed.get(partition);
                long committedOffset = offset == null ? 0L : offset;
                final long lag = Math.max(0, end.offset() - committedOffset);
                totalLag += lag;
                reporting++;

                Gauge.builder("graph.kafka.consumer.lag", () -> lag)
                        .tag("group", groupId)
                        .tag("topic", partition.topic())
                        .tag("partition", String.valueOf(partition.partition()))
                        .description("Records between the committed offset and the partition head")
                        .register(meterRegistry);
            }

            final long total = totalLag;
            final int partitionCount = reporting;
            Gauge.builder("graph.kafka.consumer.total.lag", () -> total)
                    .tag("group", groupId)
                    .description("Total records behind this consumer group across its partitions")
                    .register(meterRegistry);
            Gauge.builder("graph.kafka.consumer.monitored.partitions", () -> partitionCount)
                    .tag("group", groupId)
                    .description("Partitions included in the lag report; zero means nothing is being consumed")
                    .register(meterRegistry);

            meterRegistry.counter("graph.kafka.metrics.poll.success").increment();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            meterRegistry.counter("graph.kafka.metrics.poll.failed").increment();
        } catch (Exception e) {
            // A failed metrics poll is never an application error.
            meterRegistry.counter("graph.kafka.metrics.poll.failed").increment();
            log.debug("Consumer lag poll failed: {}", e.getMessage());
        }
    }

    /**
     * Partitions of the consumed topics, from metadata.
     *
     * <p>Sorted by topic name then partition id <em>manually</em>: {@code TopicPartition}
     * does not implement {@link Comparable}, so a {@link TreeSet} of them throws
     * {@code ClassCastException} on the first insertion — which, because the whole poll
     * sits inside one try block, surfaced only as an incrementing
     * {@code poll.failed} counter with no metric ever published.
     *
     * <p>Explicit ordering also keeps the emitted gauge tag set stable between polls;
     * an unstable tag set would register a new gauge on every change.
     */
    private List<TopicPartition> discoverPartitions(AdminClient admin) throws Exception {
        Set<TopicPartition> partitions = new LinkedHashSet<>();

        var descriptions = admin.describeTopics(topics)
                .allTopicNames()
                .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        for (var description : descriptions.values()) {
            description.partitions().forEach(info ->
                    partitions.add(new TopicPartition(description.name(), info.partition())));
        }

        return partitions.stream()
                .sorted(java.util.Comparator
                        .comparing(TopicPartition::topic)
                        .thenComparingInt(TopicPartition::partition))
                .toList();
    }

    /**
     * Committed offsets for this group, empty when the group has never committed.
     *
     * <p>Not an error: on a cold start nothing is committed, and treating the
     * corresponding lag as "everything in the topic" is the correct answer.
     */
    private Map<TopicPartition, Long> fetchCommittedOffsets(AdminClient admin) {
        try {
            Map<TopicPartition, Long> offsets = new HashMap<>();
            admin.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .forEach((partition, offsetAndMetadata) -> {
                        if (offsetAndMetadata != null) {
                            offsets.put(partition, offsetAndMetadata.offset());
                        }
                    });
            return offsets;
        } catch (Exception e) {
            // An unknown or mid-rebalance group is normal; fall back to offset 0.
            log.debug("Could not read committed offsets for {}: {}", groupId, e.getMessage());
            return Map.of();
        }
    }
}
