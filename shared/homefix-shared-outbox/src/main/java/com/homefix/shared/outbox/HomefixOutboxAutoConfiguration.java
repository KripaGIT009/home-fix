package com.homefix.shared.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
 * module intentionally does not auto-declare listeners.
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

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper outboxObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Bean
    @ConditionalOnBean(OutboxEventRepository.class)
    @ConditionalOnMissingBean
    public OutboxEventPublisher outboxEventPublisher(OutboxEventRepository repository,
                                                     ObjectMapper objectMapper) {
        return new OutboxEventPublisher(repository, objectMapper);
    }

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean
    public DlqForwarder dlqForwarder(KafkaTemplate<String, String> kafkaTemplate) {
        return new DlqForwarder(kafkaTemplate);
    }
}
