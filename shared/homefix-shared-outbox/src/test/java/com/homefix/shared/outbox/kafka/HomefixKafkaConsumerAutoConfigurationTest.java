package com.homefix.shared.outbox.kafka;

import com.homefix.shared.outbox.HomefixOutboxAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.CommonLoggingErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.FixedBackOff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link HomefixKafkaConsumerAutoConfiguration} (Requirement 22.6): Spring Boot's
 * listener-container factory, which every HomeFix {@code @KafkaListener} uses, receives the
 * pausing error handler and the delivery-attempt header; both back off together when a service
 * supplies its own error handler or turns the feature off.
 */
class HomefixKafkaConsumerAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class,
                    HomefixOutboxAutoConfiguration.class, HomefixKafkaConsumerAutoConfiguration.class));

    @Test
    void bootListenerFactoryGetsPausingErrorHandlerAndAttemptHeader() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(IdempotentConsumerErrorHandler.class);
            IdempotentConsumerErrorHandler handler = context.getBean(IdempotentConsumerErrorHandler.class);
            ConcurrentKafkaListenerContainerFactory<?, ?> factory =
                    context.getBean(ConcurrentKafkaListenerContainerFactory.class);
            assertThat(ReflectionTestUtils.getField(factory, "commonErrorHandler")).isSameAs(handler);

            // Default back-off: 5 s apart, MAX_RETRIES attempts in total.
            FixedBackOff backOff = backOffOf(handler);
            assertThat(backOff.getInterval()).isEqualTo(IdempotentKafkaConsumer.RETRY_DELAY_MS);
            assertThat(backOff.getMaxAttempts()).isEqualTo(IdempotentKafkaConsumer.MAX_RETRIES - 1L);

            assertThat(attemptHeaderEnabledBy(context.getBean(ContainerCustomizer.class))).isTrue();
        });
    }

    @Test
    void retryDelayIsConfigurable() {
        runner.withPropertyValues("homefix.outbox.consumer.retry-delay=PT0.25S").run(context ->
                assertThat(backOffOf(context.getBean(IdempotentConsumerErrorHandler.class)).getInterval())
                        .isEqualTo(250L));
    }

    @Test
    void backsOffWhenServiceDeclaresItsOwnErrorHandler() {
        runner.withBean(CommonErrorHandler.class, () -> new CommonLoggingErrorHandler()).run(context -> {
            assertThat(context).doesNotHaveBean(IdempotentConsumerErrorHandler.class);
            // The attempt header is only safe alongside the handler that redelivers, so it goes too.
            assertThat(context).doesNotHaveBean(ContainerCustomizer.class);
        });
    }

    @Test
    void canBeSwitchedOff() {
        runner.withPropertyValues("homefix.outbox.consumer.error-handling.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(IdempotentConsumerErrorHandler.class);
            assertThat(context).doesNotHaveBean(ContainerCustomizer.class);
        });
        runner.withPropertyValues("homefix.outbox.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(IdempotentConsumerErrorHandler.class));
    }

    private static FixedBackOff backOffOf(IdempotentConsumerErrorHandler handler) {
        Object tracker = ReflectionTestUtils.getField(handler, "failureTracker");
        return (FixedBackOff) ReflectionTestUtils.getField(tracker, "backOff");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean attemptHeaderEnabledBy(ContainerCustomizer customizer) {
        ConcurrentMessageListenerContainer<Object, Object> container = mock(ConcurrentMessageListenerContainer.class);
        ContainerProperties properties = new ContainerProperties("any-topic");
        when(container.getContainerProperties()).thenReturn(properties);
        customizer.configure(container);
        return properties.isDeliveryAttemptHeader();
    }
}
