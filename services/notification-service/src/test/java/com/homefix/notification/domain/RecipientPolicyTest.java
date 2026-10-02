package com.homefix.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Verifies the per-event recipient mapping: who is notified of each event, which ids are required,
 * and that optional recipients are simply omitted when the event does not name them.
 */
class RecipientPolicyTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final UUID REVIEWER = UUID.randomUUID();
    private static final UUID REVIEWEE = UUID.randomUUID();

    private final RecipientPolicy policy = new RecipientPolicy();

    private static EventParticipants all() {
        return new EventParticipants(CUSTOMER, PROVIDER, REVIEWER, REVIEWEE);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, names = {
            "BOOKING_CREATED", "PROVIDER_ACCEPTED", "PROVIDER_REJECTED", "PROVIDER_ARRIVING",
            "PROVIDER_ARRIVED", "JOB_STARTED", "JOB_COMPLETED", "COMPLAINT_CREATED", "COMPLAINT_STATUS_CHANGED"})
    void customerOnlyEvents_notifyJustTheCustomerEvenWhenAProviderIsNamed(NotificationEventType type) {
        assertThat(policy.recipientsFor(type, all()))
                .containsExactly(new Recipient(CUSTOMER, NotificationAudience.CUSTOMER));
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, names = {
            "BOOKING_CREATED", "PROVIDER_ACCEPTED", "PROVIDER_REJECTED", "PROVIDER_ARRIVING",
            "PROVIDER_ARRIVED", "JOB_STARTED", "JOB_COMPLETED", "COMPLAINT_CREATED", "COMPLAINT_STATUS_CHANGED",
            "PROVIDER_ASSIGNED", "PAYMENT_COMPLETED", "BOOKING_CANCELLED"})
    void customerIsRequired(NotificationEventType type) {
        assertThatThrownBy(() -> policy.recipientsFor(type, new EventParticipants(null, PROVIDER, null, null)))
                .isInstanceOf(MissingRecipientException.class)
                .hasMessageContaining("customerId")
                .hasMessageContaining(type.eventName());
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, names = {
            "PROVIDER_ASSIGNED", "PAYMENT_COMPLETED", "BOOKING_CANCELLED"})
    void customerAndProviderEvents_notifyBoth(NotificationEventType type) {
        assertThat(policy.recipientsFor(type, all()))
                .extracting(Recipient::userId, Recipient::audience)
                .containsExactly(tuple(CUSTOMER, NotificationAudience.CUSTOMER),
                        tuple(PROVIDER, NotificationAudience.PROVIDER));
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, names = {
            "PROVIDER_ASSIGNED", "PAYMENT_COMPLETED", "BOOKING_CANCELLED"})
    void providerIsOptional(NotificationEventType type) {
        // A booking cancelled before any provider was assigned has no provider to tell.
        assertThat(policy.recipientsFor(type, new EventParticipants(CUSTOMER, null, null, null)))
                .containsExactly(new Recipient(CUSTOMER, NotificationAudience.CUSTOMER));
    }

    @Test
    void reviewSubmitted_notifiesReviewerAndRevieweeEachWhenNamed() {
        assertThat(policy.recipientsFor(NotificationEventType.REVIEW_SUBMITTED, all()))
                .containsExactly(new Recipient(REVIEWER, NotificationAudience.REVIEWER),
                        new Recipient(REVIEWEE, NotificationAudience.REVIEWEE));

        assertThat(policy.recipientsFor(NotificationEventType.REVIEW_SUBMITTED,
                new EventParticipants(null, null, null, REVIEWEE)))
                .containsExactly(new Recipient(REVIEWEE, NotificationAudience.REVIEWEE));
    }

    @Test
    void reviewSubmitted_withNeitherPartyIsMissingRecipient() {
        assertThatThrownBy(() -> policy.recipientsFor(NotificationEventType.REVIEW_SUBMITTED,
                new EventParticipants(CUSTOMER, PROVIDER, null, null)))
                .isInstanceOf(MissingRecipientException.class)
                .hasMessageContaining("reviewerId or revieweeId");
    }

    @ParameterizedTest
    @EnumSource(NotificationEventType.class)
    void audiencesForMatchesWhatRecipientsForProduces(NotificationEventType type) {
        List<Recipient> recipients = policy.recipientsFor(type, all());

        assertThat(recipients).isNotEmpty();
        assertThat(recipients).extracting(Recipient::audience)
                .containsExactlyInAnyOrderElementsOf(policy.audiencesFor(type));
    }
}
