package com.homefix.shared.outbox.kafka;

import com.homefix.shared.outbox.HomefixOutboxAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DefaultErrorHandler;

import java.time.Duration;

/**
 * Wires the listener-container side of {@link IdempotentKafkaConsumer}'s retry contract
 * (Requirement 22.6) into Spring Boot's auto-configured {@code kafkaListenerContainerFactory},
 * which every HomeFix {@code @KafkaListener} uses:
 *
 * <ul>
 *   <li>an {@link IdempotentConsumerErrorHandler} as the factory's {@link CommonErrorHandler}
 *       (Boot applies a unique {@code CommonErrorHandler} bean to the factory), pausing the
 *       listener for {@code homefix.outbox.consumer.retry-delay} (default 5 s) between attempts
 *       instead of sleeping on its thread;</li>
 *   <li>a container customizer turning on the delivery-attempt header, so
 *       {@link IdempotentKafkaConsumer#consume} knows which attempt it is running and dead-letters
 *       on the last one.</li>
 * </ul>
 *
 * <p>The two are registered together and only when the service declares no
 * {@code CommonErrorHandler} of its own: the consumer rethrows non-final failures on the strength
 * of the attempt header, which is only safe when the error handler is known to redeliver them.
 * A service that builds its own container factory gets neither, and the consumer then falls back
 * to running its attempts back to back.
 *
 * <p>Disable with {@code homefix.outbox.consumer.error-handling.enabled=false} (or the whole outbox
 * module with {@code homefix.outbox.enabled=false}).
 */
@AutoConfiguration(after = {KafkaAutoConfiguration.class, HomefixOutboxAutoConfiguration.class})
@ConditionalOnClass(DefaultErrorHandler.class)
@ConditionalOnProperty(prefix = "homefix.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HomefixKafkaConsumerAutoConfiguration {

    /** Property holding the pause between consumer attempts (ISO-8601 or Boot duration syntax). */
    public static final String RETRY_DELAY_PROPERTY = "homefix.outbox.consumer.retry-delay";

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "homefix.outbox.consumer.error-handling", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    static class IdempotentConsumerErrorHandling {

        @Bean
        IdempotentConsumerErrorHandler idempotentConsumerErrorHandler(
                ObjectProvider<DlqForwarder> dlqForwarder, Environment environment) {
            Duration retryDelay = Binder.get(environment).bind(RETRY_DELAY_PROPERTY, Duration.class)
                    .orElse(Duration.ofMillis(IdempotentKafkaConsumer.RETRY_DELAY_MS));
            return IdempotentConsumerErrorHandler.create(dlqForwarder::getIfAvailable, retryDelay);
        }

        @Bean
        @ConditionalOnMissingBean(ContainerCustomizer.class)
        ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>>
                deliveryAttemptHeaderCustomizer() {
            return container -> container.getContainerProperties().setDeliveryAttemptHeader(true);
        }
    }
}
