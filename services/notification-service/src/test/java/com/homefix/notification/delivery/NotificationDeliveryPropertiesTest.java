package com.homefix.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.homefix.notification.domain.NotificationContact;
import com.homefix.notification.domain.NotificationEvent;
import com.homefix.notification.domain.NotificationEventType;
import com.homefix.notification.domain.RetrySchedule;
import com.homefix.notification.support.TestDoubles.FixedPreferencePort;
import com.homefix.notification.support.TestDoubles.InMemoryDeliveryLogStore;
import com.homefix.notification.support.TestDoubles.RecordingEmailPort;
import com.homefix.notification.support.TestDoubles.RecordingInAppPort;
import com.homefix.notification.support.TestDoubles.RecordingPushPort;
import com.homefix.notification.support.TestDoubles.RecordingSmsPort;

import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-based test for the Notification Service correctness property 22 (design.md "Correctness
 * Properties", Requirements 17.7 / 22.5). Runs a minimum of 100 tries and is tagged with the
 * required {@code Feature: homefix-platform, Property N} label.
 *
 * <p>Complements the example-based {@link NotificationDeliveryServiceTest} by asserting the
 * per-channel deduplication invariant holds universally across every lifecycle event type and any
 * number of Kafka redeliveries: once an event has been processed for a channel, no further
 * redelivery of that same {@code kafkaEventId} ever dispatches again on that channel.
 *
 * <p>The real {@link NotificationDeliveryService} is exercised against in-memory test doubles (no
 * Spring, no Kafka), with an {@link InMemoryDeliveryLogStore} enforcing the production
 * {@code (kafkaEventId, channel)} uniqueness.
 */
class NotificationDeliveryPropertiesTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    // ============================================================================================
    // Property 22: Notification delivery deduplication
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 22: Notification delivery deduplication")
    void redeliveredEventNeverDispatchesDuplicatePerChannel(
            @ForAll NotificationEventType eventType,
            @ForAll @IntRange(min = 1, max = 6) int redeliveries) {

        InMemoryDeliveryLogStore store = new InMemoryDeliveryLogStore();
        RecordingSmsPort sms = new RecordingSmsPort();
        RecordingEmailPort email = new RecordingEmailPort();
        RecordingPushPort push = new RecordingPushPort();
        RecordingInAppPort inApp = new RecordingInAppPort();

        ChannelDispatcher dispatcher = new ChannelDispatcher(sms, email, push, inApp);
        // Preference data unavailable -> all channels enabled, so every candidate channel is a
        // real dispatch attempt on the first delivery (maximises the dedup surface under test).
        NotificationDeliveryService svc = new NotificationDeliveryService(
                new com.homefix.notification.domain.EventTemplateResolver(),
                FixedPreferencePort.unavailable(), store, dispatcher,
                new RetrySchedule(3, Duration.ofSeconds(1)), FIXED_CLOCK, new ArrayList<Long>()::add);

        UUID kafkaEventId = UUID.randomUUID();

        // First delivery: dispatch fans out to the event's candidate channels.
        svc.dispatch(event(eventType, kafkaEventId));

        int smsAfterFirst = sms.attempts.get();
        int emailAfterFirst = email.attempts.get();
        int pushAfterFirst = push.attempts.get();
        int inAppAfterFirst = inApp.attempts.get();

        // At least one channel must have been dispatched on the first delivery (every event
        // produces a notification), otherwise there is nothing to deduplicate.
        assertThat(smsAfterFirst + emailAfterFirst + pushAfterFirst + inAppAfterFirst)
                .as("event %s should dispatch at least once on first delivery", eventType)
                .isGreaterThanOrEqualTo(1);

        int recordsAfterFirst = store.records.size();

        // Redeliver the SAME kafkaEventId N times: every channel is already recorded, so no
        // channel is dispatched again (Property 22).
        for (int i = 0; i < redeliveries; i++) {
            svc.dispatch(event(eventType, kafkaEventId));
        }

        assertThat(sms.attempts.get()).isEqualTo(smsAfterFirst);
        assertThat(email.attempts.get()).isEqualTo(emailAfterFirst);
        assertThat(push.attempts.get()).isEqualTo(pushAfterFirst);
        assertThat(inApp.attempts.get()).isEqualTo(inAppAfterFirst);
        // No new delivery-log rows were written by the redeliveries either.
        assertThat(store.records.size()).isEqualTo(recordsAfterFirst);
    }

    private NotificationEvent event(NotificationEventType type, UUID kafkaEventId) {
        return new NotificationEvent(
                type, kafkaEventId, UUID.randomUUID(),
                new NotificationContact("+919876543210", "user@example.com", "device-token"),
                Map.of("bookingReference", "BR-42"));
    }
}
