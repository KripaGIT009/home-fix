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

import com.homefix.notification.channel.EmailPort;
import com.homefix.notification.channel.InAppPort;
import com.homefix.notification.channel.PushPort;
import com.homefix.notification.channel.SmsPort;
import com.homefix.notification.domain.DeliveryStatus;
import com.homefix.notification.domain.EventTemplateResolver;
import com.homefix.notification.domain.NotificationAudience;
import com.homefix.notification.domain.NotificationChannel;
import com.homefix.notification.domain.NotificationContact;
import com.homefix.notification.domain.NotificationEvent;
import com.homefix.notification.domain.NotificationEventType;
import com.homefix.notification.domain.NotificationPreferences;
import com.homefix.notification.domain.RecipientPolicy;
import com.homefix.notification.domain.RetrySchedule;
import com.homefix.notification.support.TestDoubles.AlwaysFailingSmsPort;
import com.homefix.notification.support.TestDoubles.FixedPreferencePort;
import com.homefix.notification.support.TestDoubles.InMemoryDeliveryLogStore;
import com.homefix.notification.support.TestDoubles.RecordingEmailPort;
import com.homefix.notification.support.TestDoubles.RecordingInAppPort;
import com.homefix.notification.support.TestDoubles.RecordingPushPort;
import com.homefix.notification.support.TestDoubles.RecordingSmsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Behavioural tests for the multi-channel dispatch orchestrator: deduplication, preference
 * enforcement, retry schedule, per-recipient dedup, skipping channels the recipient has no address
 * on, and delivery for every event (Requirement 17, Property 22).
 */
class NotificationDeliveryServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    /** One recipient for the single-recipient tests, so a redelivery addresses the same user. */
    private static final UUID RECIPIENT = UUID.randomUUID();

    private final EventTemplateResolver resolver = new EventTemplateResolver();

    private NotificationEvent event(NotificationEventType type, UUID eventId) {
        return event(type, eventId, RECIPIENT,
                new NotificationContact("+919876543210", "user@example.com", "device-token"));
    }

    private NotificationEvent event(NotificationEventType type, UUID eventId, UUID recipient,
                                    NotificationContact contact) {
        // Address the event to an audience the recipient policy actually produces for the type.
        NotificationAudience audience = new RecipientPolicy().audiencesFor(type).iterator().next();
        return new NotificationEvent(type, eventId, recipient, audience, contact,
                Map.of("bookingReference", "BR-42"));
    }

    private NotificationDeliveryService service(InMemoryDeliveryLogStore store,
                                                FixedPreferencePort preferences,
                                                SmsPort sms, EmailPort email, PushPort push,
                                                InAppPort inApp,
                                                RetrySchedule schedule,
                                                List<Long> sleeps) {
        ChannelDispatcher dispatcher = new ChannelDispatcher(sms, email, push, inApp);
        return new NotificationDeliveryService(resolver, preferences, store, dispatcher, schedule,
                FIXED_CLOCK, sleeps::add);
    }

    @Test
    void duplicateEventIdIsDiscardedWithoutDispatch() {
        UUID eventId = UUID.randomUUID();
        InMemoryDeliveryLogStore store = new InMemoryDeliveryLogStore();
        RecordingSmsPort sms = new RecordingSmsPort();
        RecordingEmailPort email = new RecordingEmailPort();
        RecordingPushPort push = new RecordingPushPort();
        RecordingInAppPort inApp = new RecordingInAppPort();
        List<Long> sleeps = new ArrayList<>();

        NotificationDeliveryService svc = service(store, FixedPreferencePort.unavailable(),
                sms, email, push, inApp, new RetrySchedule(3, Duration.ofSeconds(1)), sleeps);

        // PAYMENT_COMPLETED fans out to all four channels.
        svc.dispatch(event(NotificationEventType.PAYMENT_COMPLETED, eventId));
        int smsAfterFirst = sms.attempts.get();

        // Redeliver the SAME event id: every channel is already logged, so nothing is re-sent.
        svc.dispatch(event(NotificationEventType.PAYMENT_COMPLETED, eventId));

        assertThat(sms.attempts.get()).isEqualTo(smsAfterFirst);
        assertThat(email.attempts.get()).isEqualTo(1);
        assertThat(push.attempts.get()).isEqualTo(1);
        assertThat(inApp.attempts.get()).isEqualTo(1);
    }

    @Test
    void preferenceDisabledChannelIsSkipped() {
        InMemoryDeliveryLogStore store = new InMemoryDeliveryLogStore();
        RecordingSmsPort sms = new RecordingSmsPort();
        RecordingEmailPort email = new RecordingEmailPort();
        RecordingPushPort push = new RecordingPushPort();
        RecordingInAppPort inApp = new RecordingInAppPort();

        // User enables only PUSH and IN_APP -> SMS and EMAIL must be skipped.
        FixedPreferencePort prefs = FixedPreferencePort.of(
                NotificationPreferences.of(NotificationChannel.PUSH, NotificationChannel.IN_APP));

        NotificationDeliveryService svc = service(store, prefs, sms, email, push, inApp,
                new RetrySchedule(3, Duration.ofSeconds(1)), new ArrayList<>());

        svc.dispatch(event(NotificationEventType.PAYMENT_COMPLETED, UUID.randomUUID()));

        assertThat(sms.attempts.get()).isZero();
        assertThat(email.attempts.get()).isZero();
        assertThat(push.attempts.get()).isEqualTo(1);
        assertThat(inApp.attempts.get()).isEqualTo(1);

        assertThat(store.records)
                .filteredOn(r -> r.getDeliveryStatus() == DeliveryStatus.SKIPPED_PREFERENCE)
                .extracting(DeliveryLogEntity::getChannel)
                .containsExactlyInAnyOrder(NotificationChannel.SMS, NotificationChannel.EMAIL);
    }

    @Test
    void unavailablePreferenceDefaultsToAllChannelsEnabled() {
        InMemoryDeliveryLogStore store = new InMemoryDeliveryLogStore();
        RecordingSmsPort sms = new RecordingSmsPort();
        RecordingEmailPort email = new RecordingEmailPort();
        RecordingPushPort push = new RecordingPushPort();
        RecordingInAppPort inApp = new RecordingInAppPort();

        NotificationDeliveryService svc = service(store, FixedPreferencePort.unavailable(),
                sms, email, push, inApp, new RetrySchedule(3, Duration.ofSeconds(1)), new ArrayList<>());

        svc.dispatch(event(NotificationEventType.PAYMENT_COMPLETED, UUID.randomUUID()));

        // All four channels delivered because preference data was unavailable.
        assertThat(sms.attempts.get()).isEqualTo(1);
        assertThat(email.attempts.get()).isEqualTo(1);
        assertThat(push.attempts.get()).isEqualTo(1);
        assertThat(inApp.attempts.get()).isEqualTo(1);
    }

    @Test
    void retryBackoffFollowsOneTwoFourAndSucceedsOnThirdAttempt() {
        InMemoryDeliveryLogStore store = new InMemoryDeliveryLogStore();
        // SMS fails twice then succeeds on the 3rd attempt.
        RecordingSmsPort sms = new RecordingSmsPort(2);
        List<Long> sleeps = new ArrayList<>();

        // Only SMS enabled so we isolate the retry behaviour on one channel.
        FixedPreferencePort prefs = FixedPreferencePort.of(
                NotificationPreferences.of(NotificationChannel.SMS));

        NotificationDeliveryService svc = service(store, prefs, sms, new RecordingEmailPort(),
                new RecordingPushPort(), new RecordingInAppPort(),
                new RetrySchedule(3, Duration.ofSeconds(1)), sleeps);

        svc.dispatch(event(NotificationEventType.PROVIDER_ACCEPTED, UUID.randomUUID()));

        assertThat(sms.attempts.get()).isEqualTo(3);
        // Two backoffs between the three attempts: 1 s then 2 s.
        assertThat(sleeps).containsExactly(1_000L, 2_000L);
        assertThat(store.records)
                .filteredOn(r -> r.getChannel() == NotificationChannel.SMS)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
                    assertThat(r.getRetryCount()).isEqualTo(2);
                });
    }

    @Test
    void permanentFailureAfterThreeFailedAttempts() {
        InMemoryDeliveryLogStore store = new InMemoryDeliveryLogStore();
        AlwaysFailingSmsPort sms = new AlwaysFailingSmsPort();
        List<Long> sleeps = new ArrayList<>();

        FixedPreferencePort prefs = FixedPreferencePort.of(
                NotificationPreferences.of(NotificationChannel.SMS));

        NotificationDeliveryService svc = service(store, prefs, sms, new RecordingEmailPort(),
                new RecordingPushPort(), new RecordingInAppPort(),
                new RetrySchedule(3, Duration.ofSeconds(1)), sleeps);

        svc.dispatch(event(NotificationEventType.PROVIDER_ACCEPTED, UUID.randomUUID()));

        assertThat(sms.attempts.get()).isEqualTo(3);
        assertThat(sleeps).containsExactly(1_000L, 2_000L);
        assertThat(store.records)
                .filteredOn(r -> r.getChannel() == NotificationChannel.SMS)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.PERMANENTLY_FAILED);
                    assertThat(r.getRetryCount()).isEqualTo(3);
                    assertThat(r.getErrorDescription()).isNotBlank();
                });
    }

    @Test
    void sameEventToTwoRecipients_deliversToEachAndDedupsEachIndependently() {
        // A cancellation reaches the customer and the provider on the same channels under one
        // Kafka event id; the first recipient's log rows must not suppress the second's delivery.
        UUID eventId = UUID.randomUUID();
        InMemoryDeliveryLogStore store = new InMemoryDeliveryLogStore();
        RecordingSmsPort sms = new RecordingSmsPort();
        RecordingInAppPort inApp = new RecordingInAppPort();
        NotificationDeliveryService svc = service(store, FixedPreferencePort.unavailable(),
                sms, new RecordingEmailPort(), new RecordingPushPort(), inApp,
                new RetrySchedule(3, Duration.ofSeconds(1)), new ArrayList<>());
        NotificationContact phoneOnly = new NotificationContact("+919000000001", null, null);
        NotificationEvent toCustomer = new NotificationEvent(NotificationEventType.BOOKING_CANCELLED, eventId,
                UUID.randomUUID(), NotificationAudience.CUSTOMER, phoneOnly, Map.of());
        NotificationEvent toProvider = new NotificationEvent(NotificationEventType.BOOKING_CANCELLED, eventId,
                UUID.randomUUID(), NotificationAudience.PROVIDER, phoneOnly, Map.of());

        svc.dispatch(toCustomer);
        svc.dispatch(toProvider);
        assertThat(sms.attempts.get()).isEqualTo(2);
        assertThat(inApp.attempts.get()).isEqualTo(2);

        // Redelivery of the whole event: nothing is re-sent to either recipient.
        svc.dispatch(toCustomer);
        svc.dispatch(toProvider);
        assertThat(sms.attempts.get()).isEqualTo(2);
        assertThat(inApp.attempts.get()).isEqualTo(2);
    }

    @Test
    void channelWithoutAddress_isSkippedAndRecordedWithoutRetries() {
        InMemoryDeliveryLogStore store = new InMemoryDeliveryLogStore();
        RecordingSmsPort sms = new RecordingSmsPort();
        RecordingEmailPort email = new RecordingEmailPort();
        RecordingPushPort push = new RecordingPushPort();
        RecordingInAppPort inApp = new RecordingInAppPort();
        List<Long> sleeps = new ArrayList<>();
        NotificationDeliveryService svc = service(store, FixedPreferencePort.unavailable(),
                sms, email, push, inApp, new RetrySchedule(3, Duration.ofSeconds(1)), sleeps);

        // What the Auth Service can supply today: a phone number, no email, no push token.
        svc.dispatch(event(NotificationEventType.PAYMENT_COMPLETED, UUID.randomUUID(), UUID.randomUUID(),
                new NotificationContact("+919000000001", null, null)));

        assertThat(sms.attempts.get()).isEqualTo(1);
        assertThat(inApp.attempts.get()).isEqualTo(1);
        assertThat(email.attempts.get()).isZero();
        assertThat(push.attempts.get()).isZero();
        assertThat(sleeps).as("no retry backoff for an address that does not exist").isEmpty();
        assertThat(store.records)
                .filteredOn(r -> r.getDeliveryStatus() == DeliveryStatus.SKIPPED_NO_CONTACT)
                .extracting(DeliveryLogEntity::getChannel)
                .containsExactlyInAnyOrder(NotificationChannel.EMAIL, NotificationChannel.PUSH);
    }

    @Test
    void recipientWithNoContactAtAll_stillGetsInAppNotification() {
        InMemoryDeliveryLogStore store = new InMemoryDeliveryLogStore();
        RecordingInAppPort inApp = new RecordingInAppPort();
        RecordingSmsPort sms = new RecordingSmsPort();
        NotificationDeliveryService svc = service(store, FixedPreferencePort.unavailable(),
                sms, new RecordingEmailPort(), new RecordingPushPort(), inApp,
                new RetrySchedule(3, Duration.ofSeconds(1)), new ArrayList<>());

        svc.dispatch(event(NotificationEventType.BOOKING_CREATED, UUID.randomUUID(), UUID.randomUUID(),
                NotificationContact.empty()));

        assertThat(inApp.attempts.get()).isEqualTo(1);
        assertThat(sms.attempts.get()).isZero();
        assertThat(store.records)
                .filteredOn(r -> r.getDeliveryStatus() == DeliveryStatus.DELIVERED)
                .extracting(DeliveryLogEntity::getChannel)
                .containsExactly(NotificationChannel.IN_APP);
    }

    @ParameterizedTest
    @EnumSource(NotificationEventType.class)
    void everyLifecycleEventTriggersAtLeastOneDelivery(NotificationEventType type) {
        InMemoryDeliveryLogStore store = new InMemoryDeliveryLogStore();
        RecordingSmsPort sms = new RecordingSmsPort();
        RecordingEmailPort email = new RecordingEmailPort();
        RecordingPushPort push = new RecordingPushPort();
        RecordingInAppPort inApp = new RecordingInAppPort();

        NotificationDeliveryService svc = service(store, FixedPreferencePort.unavailable(),
                sms, email, push, inApp, new RetrySchedule(3, Duration.ofSeconds(1)), new ArrayList<>());

        svc.dispatch(event(type, UUID.randomUUID()));

        int totalDispatches = sms.attempts.get() + email.attempts.get()
                + push.attempts.get() + inApp.attempts.get();
        assertThat(totalDispatches)
                .as("event %s should dispatch at least one notification", type)
                .isGreaterThanOrEqualTo(1);
        assertThat(store.records)
                .anySatisfy(r -> assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED));
    }
}
