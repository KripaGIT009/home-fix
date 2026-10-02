package com.homefix.notification.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The built-in notification templates and the rule that picks one for an addressed event
 * (Requirements 16.2, 16.3, 17.5).
 *
 * <p>These are the texts the service has always sent. The {@code notification_template} table is
 * seeded with exactly these (V2 migration), and an admin's edit overrides the text for one
 * channel; when a row is missing the built-in text here is used, so the service never depends on
 * the table to send a correct notification.
 *
 * <p>Every audience the {@link RecipientPolicy} can produce for an event has a template here, so
 * no addressed recipient is silently dropped; a combination the policy never produces is rejected
 * loudly rather than sent someone else's text.
 */
public final class BuiltInTemplates {

    private static final Set<NotificationChannel> ALL = EnumSet.allOf(NotificationChannel.class);
    private static final Set<NotificationChannel> PUSH_IN_APP =
            EnumSet.of(NotificationChannel.PUSH, NotificationChannel.IN_APP);
    private static final Set<NotificationChannel> PUSH_SMS_IN_APP =
            EnumSet.of(NotificationChannel.PUSH, NotificationChannel.SMS, NotificationChannel.IN_APP);

    public static final TemplateDefinition BOOKING_CREATED_CUSTOMER = template(
            "BOOKING_CREATED.CUSTOMER", NotificationEventType.BOOKING_CREATED,
            "Booking created (customer)", ALL,
            "Booking confirmed",
            "We received {{bookingReference}} and are finding a professional for you.");

    public static final TemplateDefinition PROVIDER_ASSIGNED_PROVIDER = template(
            "PROVIDER_ASSIGNED.PROVIDER", NotificationEventType.PROVIDER_ASSIGNED,
            "Provider assigned (provider)", PUSH_SMS_IN_APP,
            "New job assigned",
            "You have been assigned {{bookingReference}}. Open the app for the details.");

    public static final TemplateDefinition PROVIDER_ASSIGNED_CUSTOMER = template(
            "PROVIDER_ASSIGNED.CUSTOMER", NotificationEventType.PROVIDER_ASSIGNED,
            "Provider assigned (customer)", PUSH_IN_APP,
            "Professional assigned",
            "A professional has been assigned to {{bookingReference}}.");

    /** Requirement 8.10 mandates push + SMS on acceptance. */
    public static final TemplateDefinition PROVIDER_ACCEPTED_CUSTOMER = template(
            "PROVIDER_ACCEPTED.CUSTOMER", NotificationEventType.PROVIDER_ACCEPTED,
            "Provider accepted (customer)", PUSH_SMS_IN_APP,
            "Professional on the way",
            "Your professional accepted {{bookingReference}} and is on the way.");

    /**
     * In-app only: dispatch emits one of these per candidate that declines or lets the offer
     * lapse, so a push per event would buzz the customer repeatedly while a single booking is
     * being matched.
     */
    public static final TemplateDefinition PROVIDER_REJECTED_CUSTOMER = template(
            "PROVIDER_REJECTED.CUSTOMER", NotificationEventType.PROVIDER_REJECTED,
            "Provider declined (customer)", EnumSet.of(NotificationChannel.IN_APP),
            "Finding another professional",
            "We are matching {{bookingReference}} with another professional.");

    public static final TemplateDefinition PROVIDER_ARRIVING_CUSTOMER = template(
            "PROVIDER_ARRIVING.CUSTOMER", NotificationEventType.PROVIDER_ARRIVING,
            "Provider arriving (customer)", PUSH_IN_APP,
            "Professional arriving soon",
            "Your professional for {{bookingReference}} is arriving soon.");

    public static final TemplateDefinition PROVIDER_ARRIVED_CUSTOMER = template(
            "PROVIDER_ARRIVED.CUSTOMER", NotificationEventType.PROVIDER_ARRIVED,
            "Provider arrived (customer)", PUSH_SMS_IN_APP,
            "Professional arrived",
            "Your professional for {{bookingReference}} has arrived.");

    public static final TemplateDefinition JOB_STARTED_CUSTOMER = template(
            "JOB_STARTED.CUSTOMER", NotificationEventType.JOB_STARTED,
            "Job started (customer)", PUSH_IN_APP,
            "Job started",
            "Work on {{bookingReference}} has started.");

    public static final TemplateDefinition JOB_COMPLETED_CUSTOMER = template(
            "JOB_COMPLETED.CUSTOMER", NotificationEventType.JOB_COMPLETED,
            "Job completed (customer)", ALL,
            "Job completed",
            "Work on {{bookingReference}} is complete.");

    public static final TemplateDefinition PAYMENT_COMPLETED_PROVIDER = template(
            "PAYMENT_COMPLETED.PROVIDER", NotificationEventType.PAYMENT_COMPLETED,
            "Payment completed (provider)", PUSH_IN_APP,
            "Payment received",
            "The customer's payment for {{bookingReference}} is complete. "
                    + "Your earnings will be included in your next settlement.");

    public static final TemplateDefinition PAYMENT_COMPLETED_CUSTOMER = template(
            "PAYMENT_COMPLETED.CUSTOMER", NotificationEventType.PAYMENT_COMPLETED,
            "Payment completed (customer)", ALL,
            "Payment received",
            "Payment for {{bookingReference}} was successful. Your invoice is ready.");

    public static final TemplateDefinition BOOKING_CANCELLED_PROVIDER = template(
            "BOOKING_CANCELLED.PROVIDER", NotificationEventType.BOOKING_CANCELLED,
            "Booking cancelled (provider)", PUSH_SMS_IN_APP,
            "Job cancelled",
            "{{bookingReference}} has been cancelled. You do not need to attend.");

    /** booking-service also reports "no provider found" as BookingCancelled. */
    public static final TemplateDefinition BOOKING_CANCELLED_CUSTOMER_SEARCHING_FAILED = template(
            "BOOKING_CANCELLED.CUSTOMER.SEARCHING_FAILED", NotificationEventType.BOOKING_CANCELLED,
            "No professional found (customer)", ALL,
            "No professional available",
            "We could not find a professional for {{bookingReference}}, so the booking has been closed.");

    public static final TemplateDefinition BOOKING_CANCELLED_CUSTOMER = template(
            "BOOKING_CANCELLED.CUSTOMER", NotificationEventType.BOOKING_CANCELLED,
            "Booking cancelled (customer)", ALL,
            "Booking cancelled",
            "{{bookingReference}} has been cancelled.");

    public static final TemplateDefinition REVIEW_SUBMITTED_REVIEWER = template(
            "REVIEW_SUBMITTED.REVIEWER", NotificationEventType.REVIEW_SUBMITTED,
            "Review submitted (reviewer)", PUSH_IN_APP,
            "Review submitted",
            "Thanks — your review for {{bookingReference}} was submitted.");

    public static final TemplateDefinition REVIEW_SUBMITTED_REVIEWEE = template(
            "REVIEW_SUBMITTED.REVIEWEE", NotificationEventType.REVIEW_SUBMITTED,
            "Review received (reviewee)", PUSH_IN_APP,
            "New review",
            "You received a new review for {{bookingReference}}.");

    /** Requirement 16.2: acknowledge every new complaint to the customer. */
    public static final TemplateDefinition COMPLAINT_CREATED_CUSTOMER = template(
            "COMPLAINT_CREATED.CUSTOMER", NotificationEventType.COMPLAINT_CREATED,
            "Complaint received (customer)", ALL,
            "Complaint received",
            "We received your complaint about {{bookingReference}}. "
                    + "A support agent has been assigned and will be in touch.");

    /** Requirement 16.3 mandates in-app notification of a status change. */
    public static final TemplateDefinition COMPLAINT_STATUS_CHANGED_CUSTOMER = template(
            "COMPLAINT_STATUS_CHANGED.CUSTOMER", NotificationEventType.COMPLAINT_STATUS_CHANGED,
            "Complaint status changed (customer)", PUSH_IN_APP,
            "Complaint update",
            "Your complaint about {{bookingReference}} is now {{complaintStatus}}.");

    private static final List<TemplateDefinition> ALL_TEMPLATES = List.of(
            BOOKING_CREATED_CUSTOMER,
            PROVIDER_ASSIGNED_PROVIDER,
            PROVIDER_ASSIGNED_CUSTOMER,
            PROVIDER_ACCEPTED_CUSTOMER,
            PROVIDER_REJECTED_CUSTOMER,
            PROVIDER_ARRIVING_CUSTOMER,
            PROVIDER_ARRIVED_CUSTOMER,
            JOB_STARTED_CUSTOMER,
            JOB_COMPLETED_CUSTOMER,
            PAYMENT_COMPLETED_PROVIDER,
            PAYMENT_COMPLETED_CUSTOMER,
            BOOKING_CANCELLED_PROVIDER,
            BOOKING_CANCELLED_CUSTOMER_SEARCHING_FAILED,
            BOOKING_CANCELLED_CUSTOMER,
            REVIEW_SUBMITTED_REVIEWER,
            REVIEW_SUBMITTED_REVIEWEE,
            COMPLAINT_CREATED_CUSTOMER,
            COMPLAINT_STATUS_CHANGED_CUSTOMER);

    private static final Map<String, ChannelTemplate> BY_ID = indexById();

    private BuiltInTemplates() {
    }

    /** One built-in template on one channel: the unit an admin edits. */
    public record ChannelTemplate(TemplateDefinition definition, NotificationChannel channel) {

        public String id() {
            return definition.idFor(channel);
        }
    }

    /** Every built-in template, in a stable order (event lifecycle order). */
    public static List<TemplateDefinition> all() {
        return ALL_TEMPLATES;
    }

    /** Every built-in template expanded per channel, in a stable order. */
    public static List<ChannelTemplate> allChannelTemplates() {
        return List.copyOf(BY_ID.values());
    }

    /** The per-channel template with this id, if it is one of the built-ins. */
    public static Optional<ChannelTemplate> findById(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /**
     * Picks the template for an addressed event.
     *
     * @throws IllegalStateException if the event type has no template for the recipient's
     *                               audience (guards against a policy change without a template)
     */
    public static TemplateDefinition select(NotificationEvent event) {
        NotificationAudience audience = event.audience();
        return switch (event.eventType()) {
            case BOOKING_CREATED -> customerOnly(event, BOOKING_CREATED_CUSTOMER);
            case PROVIDER_ASSIGNED -> audience == NotificationAudience.PROVIDER
                    ? PROVIDER_ASSIGNED_PROVIDER
                    : customerOnly(event, PROVIDER_ASSIGNED_CUSTOMER);
            case PROVIDER_ACCEPTED -> customerOnly(event, PROVIDER_ACCEPTED_CUSTOMER);
            case PROVIDER_REJECTED -> customerOnly(event, PROVIDER_REJECTED_CUSTOMER);
            case PROVIDER_ARRIVING -> customerOnly(event, PROVIDER_ARRIVING_CUSTOMER);
            case PROVIDER_ARRIVED -> customerOnly(event, PROVIDER_ARRIVED_CUSTOMER);
            case JOB_STARTED -> customerOnly(event, JOB_STARTED_CUSTOMER);
            case JOB_COMPLETED -> customerOnly(event, JOB_COMPLETED_CUSTOMER);
            case PAYMENT_COMPLETED -> audience == NotificationAudience.PROVIDER
                    ? PAYMENT_COMPLETED_PROVIDER
                    : customerOnly(event, PAYMENT_COMPLETED_CUSTOMER);
            case BOOKING_CANCELLED -> audience == NotificationAudience.PROVIDER
                    ? BOOKING_CANCELLED_PROVIDER
                    : customerOnly(event, "SEARCHING_FAILED".equals(event.attributes().get("bookingStatus"))
                            ? BOOKING_CANCELLED_CUSTOMER_SEARCHING_FAILED
                            : BOOKING_CANCELLED_CUSTOMER);
            case REVIEW_SUBMITTED -> switch (audience) {
                case REVIEWER -> REVIEW_SUBMITTED_REVIEWER;
                case REVIEWEE -> REVIEW_SUBMITTED_REVIEWEE;
                default -> throw noTemplate(event);
            };
            case COMPLAINT_CREATED -> customerOnly(event, COMPLAINT_CREATED_CUSTOMER);
            case COMPLAINT_STATUS_CHANGED -> customerOnly(event, COMPLAINT_STATUS_CHANGED_CUSTOMER);
        };
    }

    private static TemplateDefinition customerOnly(NotificationEvent event, TemplateDefinition template) {
        if (event.audience() != NotificationAudience.CUSTOMER) {
            throw noTemplate(event);
        }
        return template;
    }

    private static IllegalStateException noTemplate(NotificationEvent event) {
        return new IllegalStateException("No " + event.eventType().eventName()
                + " template for audience " + event.audience());
    }

    private static TemplateDefinition template(String key, NotificationEventType eventType, String name,
                                               Set<NotificationChannel> channels, String title, String body) {
        return new TemplateDefinition(key, eventType, name, channels, title, body);
    }

    private static Map<String, ChannelTemplate> indexById() {
        Map<String, ChannelTemplate> byId = new LinkedHashMap<>();
        for (TemplateDefinition definition : ALL_TEMPLATES) {
            for (NotificationChannel channel : definition.channels()) {
                ChannelTemplate template = new ChannelTemplate(definition, channel);
                if (byId.put(template.id(), template) != null) {
                    throw new IllegalStateException("Duplicate notification template id " + template.id());
                }
            }
        }
        return Collections.unmodifiableMap(byId);
    }
}
