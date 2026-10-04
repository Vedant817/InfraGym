package com.archforge.messaging;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Registers the shared messaging wiring in every service.
 *
 * <h2>Why this file has to exist</h2>
 * {@link KafkaClientConfig}, {@link KafkaTopics} and
 * {@link SubmissionLifecyclePublisher} are annotated {@code @Component} in package
 * {@code com.archforge.messaging}, which is outside every service's scan base package
 * ({@code com.archforge.graphcompiler}, {@code com.archforge.simulation}, and so on).
 * Component scanning never reaches them, so any service that injected one failed at
 * startup with {@code No qualifying bean of type 'KafkaTopics'}.
 *
 * <p>This went unnoticed for as long as it did because the graph compiler was the only
 * service wired to the messaging module, and it never injected any of these beans — it
 * used {@code @Value("${archforge.topics...}")} on its own fields, reading the same
 * properties a second time in a second place. The moment a second service was built on
 * the module, the gap became a startup failure.
 *
 * <h2>Why a library needs this rather than a service-side scan</h2>
 * Three options, and the first two are traps:
 * <ul>
 *   <li><b>Make every service scan {@code com.archforge.messaging}</b> — forces each
 *       service to know the module's package, so adding a module means editing every
 *       application class, and a service that forgets it fails only at startup.</li>
 *   <li><b>Move the beans into each service</b> — reintroduces exactly the six-way
 *       duplication this module exists to remove.</li>
 *   <li><b>Auto-configuration</b> — the standard mechanism for exactly this. Spring Boot
 *       loads it from
 *       {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports},
 *       so a service that puts the module on its classpath gets the beans with no
 *       code change at all.</li>
 * </ul>
 *
 * <p>Beans are listed explicitly rather than {@code @ComponentScan}-ed. A scan of the
 * package would pick up anything else that later lands in it, so the set of shared beans
 * would grow silently — and an accidental bean in this package would start appearing in
 * every service in the fleet without anyone changing a service pom.
 *
 * <p>{@link KafkaClientConfig}'s beans are still {@code @ConditionalOnMissingBean}, so a
 * service that needs to override one can define its own and have it win.
 */
@AutoConfiguration
@Import({
        KafkaClientConfig.class,
        KafkaTopics.class,
        SubmissionLifecyclePublisher.class,
        // Without this the wire format is whatever Boot's defaults happen to be. The
        // contracts module states the rule (Instant is ISO-8601, never epoch millis) and
        // nothing was enforcing it: this class had zero references in the repository.
        // It holds today only because Boot's defaults coincide with ContractJson, which
        // is exactly the coincidence that breaks the first time a service adds a
        // spring.jackson property of its own.
        ContractJacksonConfig.class})
public class MessagingAutoConfiguration {
}
