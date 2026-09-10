package com.homefix.notification.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Maps each of the 11 lifecycle events to its default delivery channels and renders channel-safe
 * message content (Requirement 17.5).
 *
 * <p>This is a pure function of the event type and its non-PII attributes — no I/O, no framework
 * state — so it is directly unit- and property-testable. Preference filtering happens later; this
 * resolver returns the <em>candidate</em> channels for the event.
 *
 * <p>The resolver guarantees every {@link NotificationEventType} produces a non-empty channel set
 * and rendered content, so no configured event is silently dropped.
 */
@Component
public class EventTemplateResolver {

    /**
     * Resolves the candidate channels and rendered content for an event.
     *
     * @throws IllegalStateException if an event type has no mapping (guards against a new event
     *                               type being added without a template)
     */
    public RenderedMessage resolve(NotificationEvent event) {
        Map<String, String> attrs = event.attributes();
        String ref = attrs.getOrDefault("bookingReference", "your booking");
        return switch (event.eventType()) {
            case BOOKING_CREATED -> new RenderedMessage(
                    allChannels(),
                    "Booking confirmed",
                    "We received " + ref + " and are finding a professional for you.");
            case PROVIDER_ASSIGNED -> new RenderedMessage(
                    channels(NotificationChannel.PUSH, NotificationChannel.IN_APP),
                    "Professional assigned",
                    "A professional has been assigned to " + ref + ".");
            case PROVIDER_ACCEPTED -> new RenderedMessage(
                    // Requirement 8.10 mandates push + SMS on acceptance.
                    channels(NotificationChannel.PUSH, NotificationChannel.SMS, NotificationChannel.IN_APP),
                    "Professional on the way",
                    "Your professional accepted " + ref + " and is on the way.");
            case PROVIDER_REJECTED -> new RenderedMessage(
                    channels(NotificationChannel.PUSH, NotificationChannel.IN_APP),
                    "Finding another professional",
                    "We are matching " + ref + " with another professional.");
            case PROVIDER_ARRIVING -> new RenderedMessage(
                    channels(NotificationChannel.PUSH, NotificationChannel.IN_APP),
                    "Professional arriving soon",
                    "Your professional for " + ref + " is arriving soon.");
            case PROVIDER_ARRIVED -> new RenderedMessage(
                    channels(NotificationChannel.PUSH, NotificationChannel.SMS, NotificationChannel.IN_APP),
                    "Professional arrived",
                    "Your professional for " + ref + " has arrived.");
            case JOB_STARTED -> new RenderedMessage(
                    channels(NotificationChannel.PUSH, NotificationChannel.IN_APP),
                    "Job started",
                    "Work on " + ref + " has started.");
            case JOB_COMPLETED -> new RenderedMessage(
                    allChannels(),
                    "Job completed",
                    "Work on " + ref + " is complete.");
            case PAYMENT_COMPLETED -> new RenderedMessage(
                    allChannels(),
                    "Payment received",
                    "Payment for " + ref + " was successful. Your invoice is ready.");
            case BOOKING_CANCELLED -> new RenderedMessage(
                    allChannels(),
                    "Booking cancelled",
                    ref + " has been cancelled.");
            case REVIEW_SUBMITTED -> new RenderedMessage(
                    channels(NotificationChannel.PUSH, NotificationChannel.IN_APP),
                    "Review submitted",
                    "Thanks — your review for " + ref + " was submitted.");
        };
    }

    private static Set<NotificationChannel> allChannels() {
        return EnumSet.allOf(NotificationChannel.class);
    }

    private static Set<NotificationChannel> channels(NotificationChannel... channels) {
        Set<NotificationChannel> set = EnumSet.noneOf(NotificationChannel.class);
        for (NotificationChannel channel : channels) {
            set.add(channel);
        }
        return set;
    }
}
