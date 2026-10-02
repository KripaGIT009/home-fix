package com.homefix.outbox.config;

import java.io.IOException;
import java.time.Duration;

import com.homefix.outbox.relay.EventTopicResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the shipped {@code application.yml} (not a test copy) the way Spring Boot does, so the
 * defaults operations actually get are asserted: every event type a producer writes has an
 * explicit topic, and the claim lease leaves room for a publish.
 */
class OutboxProcessorConfigurationTest {

    @Test
    void complaintEventsAreMappedToSameNamedTopicsNotTheCatchAll() throws IOException {
        EventTopicResolver resolver = new EventTopicResolver(shippedProperties().getTopics());

        // complaint-service's ComplaintCreatedEvent / ComplaintStatusChangedEvent.EVENT_TYPE.
        assertThat(resolver.resolve("ComplaintCreated")).isEqualTo("ComplaintCreated");
        assertThat(resolver.resolve("ComplaintStatusChanged")).isEqualTo("ComplaintStatusChanged");
        assertThat(resolver.resolve("SomethingUnmapped")).isEqualTo("domain-events");
    }

    @Test
    void shippedTimingsAreConsistent() throws IOException {
        OutboxProcessorProperties properties = shippedProperties();

        assertThat(properties.getClaimLease()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.getPublishTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getClaimLease()).isGreaterThan(properties.getPublishTimeout());
        assertThat(properties.getRetry().getMaxAttempts()).isEqualTo(10);
    }

    private static OutboxProcessorProperties shippedProperties() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return Binder.get(environment).bind("homefix.outbox-processor", OutboxProcessorProperties.class)
                .orElseThrow(() -> new AssertionError("homefix.outbox-processor not bound"));
    }
}
