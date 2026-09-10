package com.homefix.outbox.config;

import java.time.Clock;

import com.homefix.outbox.alert.OutboxAlertPort;
import com.homefix.outbox.relay.EventTopicResolver;
import com.homefix.outbox.relay.OutboxRelayService;
import com.homefix.outbox.relay.Sleeper;
import com.homefix.shared.outbox.OutboxEventRepository;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the outbox relay collaborators: the event-to-topic resolver, the system clock, the
 * backoff sleeper, and the {@link OutboxRelayService} itself. The relay bean is only created once
 * a {@link KafkaProducerTemplate} is available so the app can still start (for health checks and
 * config validation) before Kafka wiring is complete.
 */
@Configuration
public class RelayConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock outboxClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public Sleeper outboxSleeper() {
        return Thread::sleep;
    }

    @Bean
    @ConditionalOnMissingBean
    public EventTopicResolver eventTopicResolver(OutboxProcessorProperties properties) {
        return new EventTopicResolver(properties.getTopics());
    }

    @Bean
    // No @ConditionalOnBean here: it is only reliable on auto-configuration classes.
    // On a user @Configuration it is evaluated before Boot's KafkaAutoConfiguration has
    // registered the KafkaTemplate, so the guard silently suppressed this bean and every
    // consumer of it failed to start.
    @ConditionalOnMissingBean
    public OutboxRelayService outboxRelayService(OutboxEventRepository repository,
                                                 KafkaProducerTemplate producer,
                                                 EventTopicResolver topicResolver,
                                                 OutboxAlertPort alertPort,
                                                 OutboxProcessorProperties properties,
                                                 Clock outboxClock,
                                                 Sleeper outboxSleeper) {
        return new OutboxRelayService(repository, producer, topicResolver, alertPort,
                properties, outboxClock, outboxSleeper);
    }
}
