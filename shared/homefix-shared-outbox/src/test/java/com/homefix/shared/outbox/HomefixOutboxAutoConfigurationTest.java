package com.homefix.shared.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies the outbox auto-configuration wiring (Task 6): the publisher appears only when a
 * repository is present, the DLQ forwarder only when a {@link KafkaTemplate} is present, and
 * everything can be switched off with {@code homefix.outbox.enabled=false}.
 */
class HomefixOutboxAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HomefixOutboxAutoConfiguration.class));

    @Test
    void alwaysProvidesAnObjectMapper() {
        runner.run(context -> assertThat(context).hasSingleBean(ObjectMapper.class));
    }

    @Test
    void publisherWiredWhenRepositoryPresent() {
        runner.withBean(OutboxEventRepository.class, () -> mock(OutboxEventRepository.class))
                .run(context -> assertThat(context).hasSingleBean(OutboxEventPublisher.class));
    }

    @Test
    void publisherAbsentWhenNoRepository() {
        runner.run(context -> assertThat(context).doesNotHaveBean(OutboxEventPublisher.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dlqForwarderWiredWhenKafkaTemplatePresent() {
        runner.withBean("kafkaTemplate", KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .run(context -> assertThat(context).hasSingleBean(DlqForwarder.class));
    }

    @Test
    void dlqForwarderAbsentWithoutKafkaTemplate() {
        runner.run(context -> assertThat(context).doesNotHaveBean(DlqForwarder.class));
    }

    @Test
    void disabledRegistersNoBeans() {
        runner.withPropertyValues("homefix.outbox.enabled=false")
                .withBean(OutboxEventRepository.class, () -> mock(OutboxEventRepository.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OutboxEventPublisher.class);
                    assertThat(context).doesNotHaveBean(DlqForwarder.class);
                });
    }
}
