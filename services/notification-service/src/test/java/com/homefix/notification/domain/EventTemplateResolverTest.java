package com.homefix.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Verifies every one of the 11 lifecycle events resolves to at least one channel and non-blank
 * content, so no configured event is silently dropped (Requirement 17.5).
 */
class EventTemplateResolverTest {

    private final EventTemplateResolver resolver = new EventTemplateResolver();

    @ParameterizedTest
    @EnumSource(NotificationEventType.class)
    void everyEventTypeResolvesToDeliverableContent(NotificationEventType type) {
        NotificationEvent event = new NotificationEvent(
                type, UUID.randomUUID(), UUID.randomUUID(),
                new NotificationContact("+911", "a@b.co", "tok"),
                Map.of("bookingReference", "BR-1"));

        RenderedMessage message = resolver.resolve(event);

        assertThat(message.channels()).isNotEmpty();
        assertThat(message.title()).isNotBlank();
        assertThat(message.body()).isNotBlank();
    }

    @Test
    void allElevenEventTypesAreModelled() {
        // Guards Requirement 17.5: exactly the 11 named events are supported.
        assertThat(NotificationEventType.values()).hasSize(11);
    }

    @Test
    void providerAcceptedIncludesPushAndSms() {
        // Requirement 8.10: acceptance must reach the customer via push + SMS.
        NotificationEvent event = new NotificationEvent(
                NotificationEventType.PROVIDER_ACCEPTED, UUID.randomUUID(), UUID.randomUUID(),
                NotificationContact.empty(), Map.of());

        RenderedMessage message = resolver.resolve(event);

        assertThat(message.channels())
                .contains(NotificationChannel.PUSH, NotificationChannel.SMS);
    }
}
