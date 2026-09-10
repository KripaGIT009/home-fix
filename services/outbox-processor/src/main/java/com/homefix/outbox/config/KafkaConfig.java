package com.homefix.outbox.config;

import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka wiring for the Outbox Processor. Spring Boot auto-configures the idempotent
 * {@link KafkaTemplate} from {@code spring.kafka.producer.*} (acks=all, enable.idempotence=true);
 * this class wraps it in the shared {@link KafkaProducerTemplate}, whose constructor verifies
 * those reliability settings are present before any event is relayed.
 */
@Configuration
public class KafkaConfig {

    @Bean
    // No @ConditionalOnBean here: it is only reliable on auto-configuration classes.
    // On a user @Configuration it is evaluated before Boot's KafkaAutoConfiguration has
    // registered the KafkaTemplate, so the guard silently suppressed this bean and every
    // consumer of it failed to start.
    @ConditionalOnMissingBean
    public KafkaProducerTemplate kafkaProducerTemplate(KafkaTemplate<String, String> kafkaTemplate) {
        return new KafkaProducerTemplate(kafkaTemplate);
    }
}
