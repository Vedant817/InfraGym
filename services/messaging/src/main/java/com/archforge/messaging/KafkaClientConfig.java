package com.archforge.messaging;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * The single definition of how ArchForge talks to Kafka.
 *
 * <p>Every service used to hand-roll its own {@code producerFactory} /
 * {@code consumerFactory} / {@code errorHandler} beans. Those beans satisfy Boot's
 * {@code @ConditionalOnMissingBean}, so they silently overrode the entire
 * {@code spring.kafka.*} block in {@code application.yml}: the configuration an
 * operator tuned was dead code, and the services disagreed in ways nothing could
 * reveal — one effectively {@code acks: all}, the other {@code acks: 1}; one
 * {@code auto-offset-reset: earliest}, the other {@code latest}.
 *
 * <p>Defining it once, here, fixes five distinct defects at the same time:
 *
 * <ol>
 *   <li><b>Dead-letter routing.</b> {@code DeadLetterPublishingRecoverer}'s default
 *       resolver writes to {@code <topic>.<topic>.<partition>.DLT} — uppercase,
 *       per-partition, and declared in no catalogue — so every DLQ publish failed.
 *       The explicit resolver below routes to the declared lowercase
 *       {@code *.dlt} topics.</li>
 *   <li><b>Offset reset.</b> {@code earliest} by default. {@code latest} silently
 *       discards everything produced before a consumer group's first poll, which is
 *       precisely the window in which a freshly submitted design is lost.</li>
 *   <li><b>Idempotent producing.</b> Setting {@code retries} explicitly
 *       <em>disables</em> the implicit {@link Integer#MAX_VALUE} that idempotence
 *       installs, quietly weakening the ordering guarantee idempotence exists to
 *       provide. Idempotence wins here; {@code retries} is only set on request.</li>
 *   <li><b>Message size.</b> A legal 500-node topology compiles to ~460 KB of JSON;
 *       the 1 MB client default leaves no headroom and fails with a
 *       {@code RecordTooLargeException} that only surfaces in a log.</li>
 *   <li><b>Poll interval.</b> Explicitly raised, because a simulation runs for
 *       minutes and blowing the default 5-minute limit evicts the consumer, which
 *       replays its entire batch indefinitely.</li>
 * </ol>
 *
 * <p>There are deliberately no {@code NewTopic} beans. Topics are declared once in
 * {@code infrastructure/kafka/topics.conf} and created by the bootstrapper. A second
 * declaration can only contradict the first — and it did: replication factor 3 in
 * code against a one-broker development cluster, which fails outright.
 *
 * <p><b>Acknowledgement semantics.</b> The container uses
 * {@link ContainerProperties.AckMode#RECORD}: a record's offset is committed once
 * its handler returns. Handlers must therefore <em>not</em> call
 * {@code Acknowledgment.acknowledge()} themselves. The reason is that with manual
 * acks, a listener that throws before acknowledging leaves the offset uncommitted
 * even after the error handler has dead-lettered the record — so the same poison
 * message is redelivered forever. Letting the error handler finish and the container
 * commit makes "retried, then dead-lettered, then moved past" the only possible
 * outcome.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaClientConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaClientConfig.class);

    @Bean
    @ConditionalOnMissingBean(ProducerFactory.class)
    public ProducerFactory<String, String> archforgeProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${archforge.kafka.producer.max-request-size:2097152}") int maxRequestSizeBytes,
            @Value("${archforge.kafka.producer.retries:0}") int retries) {

        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        if (retries > 0) {
            // Explicitly requested, so accept the weaker guarantee and say so.
            config.put(ProducerConfig.RETRIES_CONFIG, retries);
            log.warn("archforge.kafka.producer.retries={} disables the idempotent "
                    + "producer's built-in unlimited retry backoff", retries);
        }
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768);
        config.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, maxRequestSizeBytes);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    @ConditionalOnMissingBean(KafkaTemplate.class)
    public KafkaTemplate<String, String> archforgeKafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    @ConditionalOnMissingBean(ConsumerFactory.class)
    public ConsumerFactory<String, String> archforgeConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id}") String groupId,
            @Value("${archforge.kafka.consumer.auto-offset-reset:earliest}") String autoOffsetReset,
            @Value("${archforge.kafka.consumer.max-poll-records:100}") int maxPollRecords,
            @Value("${archforge.kafka.consumer.max-poll-interval-ms:900000}") int maxPollIntervalMs,
            @Value("${archforge.kafka.consumer.session-timeout-ms:45000}") int sessionTimeoutMs) {

        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollIntervalMs);
        config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, sessionTimeoutMs);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * Retry with exponential backoff, then dead-letter.
     *
     * <p>Exponential rather than fixed, because a fixed interval makes every
     * consumer in the fleet retry a broker outage in lockstep — which is how a brief
     * dependency failure becomes a self-inflicted thundering herd.
     */
    @Bean
    public DefaultErrorHandler archforgeErrorHandler(KafkaTemplate<String, String> template,
                                                      KafkaTopics topics,
                                                      @Value("${archforge.kafka.error-handler.max-attempts:4}") int maxAttempts,
                                                      @Value("${archforge.kafka.error-handler.initial-interval-ms:500}") long initialIntervalMs,
                                                      @Value("${archforge.kafka.error-handler.multiplier:2.0}") double multiplier,
                                                      @Value("${archforge.kafka.error-handler.max-interval-ms:10000}") long maxIntervalMs) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template,
                // Partition -1 lets the producer choose.
                //
                // Mirroring the source partition (the obvious implementation) is wrong
                // whenever the dead-letter topic has fewer partitions than the source —
                // design.submissions has 6 and design.submissions.dlt has 3 — and the
                // producer then logs "Destination resolver returned non-existent
                // partition" on every single failure. Ordering within a dead-letter
                // topic is not required: the records are replayed individually.
                (record, exception) -> new TopicPartition(topics.deadLetterFor(record.topic()), -1));

        ExponentialBackOff backOff = new ExponentialBackOff(initialIntervalMs, multiplier);
        backOff.setMaxInterval(maxIntervalMs);
        backOff.setMaxAttempts(maxAttempts);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // A malformed payload cannot become well-formed by trying again. Retrying it
        // only delays the dead-letter write and multiplies the log noise. Transient
        // infrastructure failures are not in this list, so they still get full backoff.
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                org.springframework.messaging.converter.MessageConversionException.class,
                com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> archforgeKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler archforgeErrorHandler,
            @Value("${archforge.kafka.listener.concurrency:2}") int concurrency) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(concurrency);
        factory.setCommonErrorHandler(archforgeErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
