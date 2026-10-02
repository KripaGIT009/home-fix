package com.homefix.notification.domain;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Decides who is notified of each event (Requirement 17.4: "the relevant user").
 *
 * <p>Producers publish facts with user ids; they do not decide who hears about them. This policy
 * is the single place that does, as a pure function of the event type and the ids the event
 * carries — no I/O — so it is directly unit-testable.
 *
 * <table>
 *   <caption>Recipients per event</caption>
 *   <tr><th>Event</th><th>Recipients</th><th>Required field</th></tr>
 *   <tr><td>BookingCreated, ProviderAccepted, ProviderRejected, ProviderArriving,
 *       ProviderArrived, JobStarted, JobCompleted</td><td>customer</td><td>customerId</td></tr>
 *   <tr><td>ProviderAssigned, PaymentCompleted, BookingCancelled</td>
 *       <td>customer, and the provider when the event names one</td><td>customerId</td></tr>
 *   <tr><td>ReviewSubmitted</td><td>reviewer and reviewee, each when named</td>
 *       <td>at least one of reviewerId / revieweeId</td></tr>
 *   <tr><td>ComplaintCreated, ComplaintStatusChanged</td><td>customer</td><td>customerId</td></tr>
 * </table>
 *
 * <p>An event missing its required field raises {@link MissingRecipientException}, so it is
 * dead-lettered and replayable rather than silently dropped. An optional recipient that is absent
 * (no provider yet assigned to a cancelled booking) is simply not notified.
 */
@Component
public class RecipientPolicy {

    /**
     * @return the recipients of the event, never empty
     * @throws MissingRecipientException if the event lacks the field its recipients require
     */
    public List<Recipient> recipientsFor(NotificationEventType eventType, EventParticipants participants) {
        List<Recipient> recipients = new ArrayList<>(2);
        switch (eventType) {
            case BOOKING_CREATED, PROVIDER_ACCEPTED, PROVIDER_REJECTED, PROVIDER_ARRIVING,
                 PROVIDER_ARRIVED, JOB_STARTED, JOB_COMPLETED,
                 COMPLAINT_CREATED, COMPLAINT_STATUS_CHANGED ->
                    recipients.add(required(eventType, participants.customerId(), "customerId",
                            NotificationAudience.CUSTOMER));
            case PROVIDER_ASSIGNED, PAYMENT_COMPLETED, BOOKING_CANCELLED -> {
                recipients.add(required(eventType, participants.customerId(), "customerId",
                        NotificationAudience.CUSTOMER));
                addIfPresent(recipients, participants.providerId(), NotificationAudience.PROVIDER);
            }
            case REVIEW_SUBMITTED -> {
                addIfPresent(recipients, participants.reviewerId(), NotificationAudience.REVIEWER);
                addIfPresent(recipients, participants.revieweeId(), NotificationAudience.REVIEWEE);
                if (recipients.isEmpty()) {
                    throw new MissingRecipientException(eventType, "reviewerId or revieweeId");
                }
            }
        }
        return List.copyOf(recipients);
    }

    /**
     * Every audience this policy can address for an event type. Used to prove the template
     * resolver covers every combination that can actually occur.
     */
    public Set<NotificationAudience> audiencesFor(NotificationEventType eventType) {
        return switch (eventType) {
            case PROVIDER_ASSIGNED, PAYMENT_COMPLETED, BOOKING_CANCELLED ->
                    EnumSet.of(NotificationAudience.CUSTOMER, NotificationAudience.PROVIDER);
            case REVIEW_SUBMITTED -> EnumSet.of(NotificationAudience.REVIEWER, NotificationAudience.REVIEWEE);
            default -> EnumSet.of(NotificationAudience.CUSTOMER);
        };
    }

    private static Recipient required(NotificationEventType eventType, UUID userId, String field,
                                      NotificationAudience audience) {
        if (userId == null) {
            throw new MissingRecipientException(eventType, field);
        }
        return new Recipient(userId, audience);
    }

    private static void addIfPresent(List<Recipient> recipients, UUID userId, NotificationAudience audience) {
        if (userId != null) {
            recipients.add(new Recipient(userId, audience));
        }
    }
}
