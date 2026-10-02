package com.homefix.shared.outbox;

import com.homefix.shared.outbox.kafka.DlqForwarder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Auto-configuration that exposes the shared outbox beans to any consuming microservice that
 * has this module on the classpath (Task 6).
 *
 * <p>Wires:
 * <ul>
 *   <li>{@link OutboxEventPublisher} — always, given an {@link OutboxEventRepository}.</li>
 *   <li>{@link DlqForwarder} — when a {@link KafkaTemplate} is present.</li>
 * </ul>
 *
 * <p>Consuming services register their own {@code IdempotentKafkaConsumer} subclasses; this
 * module intentionally does not auto-declare listeners. The listener-container error handling
 * those consumers rely on for their retry delay lives in
 * {@link com.homefix.shared.outbox.kafka.HomefixKafkaConsumerAutoConfiguration}.
 *
 * <p>Ordering matters: {@code @ConditionalOnBean(KafkaTemplate.class)} can only see beans that
 * are already registered when this class is evaluated, so this auto-configuration must run after
 * {@link KafkaAutoConfiguration}. Without that, a service which relies on Boot's auto-configured
 * {@code KafkaTemplate} (rather than declaring its own {@code KafkaConfig}) silently gets no
 * {@link DlqForwarder}, and its consumers fail to start with an unsatisfied dependency.
 *
 * <p>Disable everything with {@code homefix.outbox.enabled=false}.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnProperty(prefix = "homefix.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HomefixOutboxAutoConfiguration {

    /**
     * Note the {@code ObjectMapper} is injected, never declared by this module.
     *
     * <p>This auto-configuration used to supply its own {@code new ObjectMapper()} behind
     * {@code @ConditionalOnMissingBean} as a convenience. Declaring any {@code ObjectMapper}
     * bean makes Spring Boot's {@code JacksonAutoConfiguration} back off, so that convenience
     * bean did not just serve the outbox -- it replaced <em>the</em> mapper behind every
     * {@code @RequestBody} and {@code @ResponseBody} in all nineteen services that depend on
     * this module, with none of Boot's defaults applied. Two of those defaults matter:
     *
     * <ul>
     *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES} stayed at Jackson's {@code true}, so any request
     *       carrying a field its DTO does not declare was rejected with an opaque 400 rather
     *       than having the field ignored.</li>
     *   <li>{@code WRITE_DATES_AS_TIMESTAMPS} stayed at {@code true}, so responses serialised
     *       {@code Instant} as an epoch number instead of an ISO-8601 string.</li>
     * </ul>
     *
     * <p>Every consumer is a Spring Boot application with Jackson on the classpath, so Boot
     * always provides an {@code ObjectMapper}; there is nothing for this module to fall back
     * to. Do not reintroduce one here -- configure Jackson in the service, or via
     * {@code spring.jackson.*}, where it applies to the HTTP layer deliberately.
     */
    @Bean
    @ConditionalOnBean(OutboxEventRepository.class)
    @ConditionalOnMissingBean
    public OutboxEventPublisher outboxEventPublisher(OutboxEventRepository repository,
                                                     com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new OutboxEventPublisher(repository, objectMapper);
    }

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean
    public DlqForwarder dlqForwarder(KafkaTemplate<String, String> kafkaTemplate) {
        return new DlqForwarder(kafkaTemplate);
    }
}
