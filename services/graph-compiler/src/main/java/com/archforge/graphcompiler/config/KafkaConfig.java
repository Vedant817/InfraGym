package com.archforge.graphcompiler.config;

import com.archforge.messaging.KafkaTopics;
import com.archforge.contracts.event.TopicNames;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Graph-compiler Kafka settings.
 *
 * <p>Producer, consumer and container factories live in the shared
 * {@code KafkaClientConfig}; there are no {@code NewTopic} beans here on purpose.
 *
 * <p>Topic beans were the single most damaging piece of duplicated configuration in
 * the codebase: they declared replication factor 3 against a one-broker development
 * cluster (so creation failed outright) and declared 3 partitions where the topic
 * catalogue declares 6 — meaning any capacity reasoning based on the code was
 * wrong, and if the bootstrapper lost the race the service started with no topics at
 * all. {@code infrastructure/kafka/topics.conf} is the only place a topic is
 * declared now.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "archforge.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConfig {

    /**
     * Documented, but not used to create anything: the bootstrapper owns topic
     * creation. Exposed so the admin endpoints can report which topic a given
     * dead-letter record should be replayed onto.
     */
    public String deadLetterTopic(KafkaTopics topics) {
        return topics.deadLetterFor(topics.designSubmissions());
    }

    /** Kept for symmetry with the other services' documentation. */
    public String submissionsTopic() {
        return TopicNames.DESIGN_SUBMISSIONS;
    }
}
