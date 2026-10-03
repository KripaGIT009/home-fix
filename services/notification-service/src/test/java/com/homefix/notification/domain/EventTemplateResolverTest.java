package com.homefix.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Verifies every event resolves to at least one channel and non-blank content for every audience
 * the {@link RecipientPolicy} can address it to, so no addressed recipient is silently dropped
 * (Requirements 16.2, 16.3, 17.5), and that recipients get the text meant for them.
 */
class EventTemplateResolverTest {

    private final EventTemplateResolver resolver = new EventTemplateResolver();
    private final RecipientPolicy policy = new RecipientPolicy();

    private static NotificationEvent event(NotificationEventType type, NotificationAudience audience,
                                           Map<String, String> attributes) {
        return new NotificationEvent(type, UUID.randomUUID(), UUID.randomUUID(), audience,
                new NotificationContact("+911", "a@b.co", "tok"), attributes);
    }

    @ParameterizedTest
    @EnumSource(NotificationEventType.class)
    void everyAudienceThePolicyAddressesResolvesToDeliverableContent(NotificationEventType type) {
        assertThat(policy.audiencesFor(type)).isNotEmpty();
        for (NotificationAudience audience : policy.audiencesFor(type)) {
            RenderedMessage message = resolver.resolve(event(type, audience, Map.of("bookingReference", "BR-1")));

            assertThat(message.channels()).as("%s to %s", type, audience).isNotEmpty();
            assertThat(message.title()).isNotBlank();
            assertThat(message.body()).isNotBlank();
        }
    }

    @Test
    void lifecycleAndComplaintEventTypesAreModelled() {
        // Requirement 17.5's 11 lifecycle events plus the two complaint events (16.2, 16.3).
        assertThat(NotificationEventType.values()).hasSize(13);
    }

    @Test
    void providerAcceptedIncludesPushAndSms() {
        // Requirement 8.10: acceptance must reach the customer via push + SMS.
        RenderedMessage message = resolver.resolve(
                event(NotificationEventType.PROVIDER_ACCEPTED, NotificationAudience.CUSTOMER, Map.of()));

        assertThat(message.channels())
                .contains(NotificationChannel.PUSH, NotificationChannel.SMS);
    }

    @Test
    void cancellationTellsTheProviderNotToAttendAndTheCustomerItIsCancelled() {
        RenderedMessage toProvider = resolver.resolve(event(NotificationEventType.BOOKING_CANCELLED,
                NotificationAudience.PROVIDER, Map.of("bookingReference", "HFX-7")));
        RenderedMessage toCustomer = resolver.resolve(event(NotificationEventType.BOOKING_CANCELLED,
                NotificationAudience.CUSTOMER, Map.of("bookingReference", "HFX-7")));

        assertThat(toProvider.body()).contains("HFX-7").contains("do not need to attend");
        assertThat(toCustomer.body()).contains("HFX-7").doesNotContain("attend");
    }

    @Test
    void searchingFailedIsToldAsNoProfessionalRatherThanACancellation() {
        RenderedMessage message = resolver.resolve(event(NotificationEventType.BOOKING_CANCELLED,
                NotificationAudience.CUSTOMER, Map.of("bookingReference", "HFX-8", "bookingStatus", "SEARCHING_FAILED")));

        assertThat(message.title()).isEqualTo("No professional available");
        assertThat(message.body()).contains("HFX-8");
    }

    @Test
    void aTenantAssignedJobTellsTheProviderWhichAgencyAssignedIt() {
        // Requirement MT-5.3: the provider learns who assigned the job, and that it awaits their answer.
        RenderedMessage message = resolver.resolve(event(NotificationEventType.PROVIDER_ASSIGNED,
                NotificationAudience.PROVIDER,
                Map.of("bookingReference", "HFX-9", "tenantName", "Sharma Home Services")));

        assertThat(message.title()).isEqualTo("New job from Sharma Home Services");
        assertThat(message.body()).isEqualTo(
                "HFX-9 has been assigned to you by Sharma Home Services. Open the app to accept or decline it.");
        for (NotificationChannel channel : message.channels()) {
            assertThat(message.bodyFor(channel)).as(channel.name()).contains("by Sharma Home Services");
        }
        assertThat(message.channels()).contains(NotificationChannel.PUSH, NotificationChannel.SMS);
    }

    @Test
    void anAssignmentWithoutAnAgencyKeepsThePlainProviderText() {
        RenderedMessage message = resolver.resolve(event(NotificationEventType.PROVIDER_ASSIGNED,
                NotificationAudience.PROVIDER, Map.of("bookingReference", "HFX-10")));

        assertThat(message.title()).isEqualTo("New job assigned");
        assertThat(message.body()).isEqualTo("You have been assigned HFX-10. Open the app to accept or decline it.")
                .doesNotContain("{{");
    }

    @Test
    void theCustomerIsToldTheAssignedProfessionalStillHasToConfirm() {
        // The booking rests in PROVIDER_ASSIGNED until the provider accepts: not "on the way" yet.
        RenderedMessage message = resolver.resolve(event(NotificationEventType.PROVIDER_ASSIGNED,
                NotificationAudience.CUSTOMER,
                Map.of("bookingReference", "HFX-11", "tenantName", "Sharma Home Services")));

        assertThat(message.title()).isEqualTo("Professional assigned");
        assertThat(message.body()).isEqualTo(
                "A professional has been assigned to HFX-11. We will let you know as soon as they confirm.");
    }

    @Test
    void anAdminTextUsingTheAgencyNameOnThePlainVariantNamesThePlatformInstead() {
        EventTemplateResolver edited = new EventTemplateResolver(id -> id.equals("PROVIDER_ASSIGNED.PROVIDER.PUSH")
                ? Optional.of(new TemplateText("From {{tenantName}}", "{{bookingReference}} from {{tenantName}}"))
                : Optional.empty());

        RenderedMessage message = edited.resolve(event(NotificationEventType.PROVIDER_ASSIGNED,
                NotificationAudience.PROVIDER, Map.of("bookingReference", "HFX-12")));

        assertThat(message.bodyFor(NotificationChannel.PUSH)).isEqualTo("HFX-12 from HomeFix");
    }

    @Test
    void reviewRecipientsGetDifferentMessages() {
        RenderedMessage reviewer = resolver.resolve(
                event(NotificationEventType.REVIEW_SUBMITTED, NotificationAudience.REVIEWER, Map.of()));
        RenderedMessage reviewee = resolver.resolve(
                event(NotificationEventType.REVIEW_SUBMITTED, NotificationAudience.REVIEWEE, Map.of()));

        assertThat(reviewer.title()).isEqualTo("Review submitted");
        assertThat(reviewee.title()).isEqualTo("New review");
    }

    @Test
    void complaintStatusChangeIsInAppAndNamesTheStatusReadably() {
        // Requirement 16.3: status changes reach the customer in-app.
        RenderedMessage message = resolver.resolve(event(NotificationEventType.COMPLAINT_STATUS_CHANGED,
                NotificationAudience.CUSTOMER, Map.of("complaintStatus", "REFUND_FAILED")));

        assertThat(message.channels()).contains(NotificationChannel.IN_APP);
        assertThat(message.body()).contains("refund failed");
    }

    @Test
    void complaintCreatedAcknowledgesTheCustomer() {
        // Requirement 16.2.
        RenderedMessage message = resolver.resolve(
                event(NotificationEventType.COMPLAINT_CREATED, NotificationAudience.CUSTOMER, Map.of()));

        assertThat(message.title()).isEqualTo("Complaint received");
        assertThat(message.channels()).contains(NotificationChannel.SMS, NotificationChannel.IN_APP);
    }

    @Test
    void anAudienceThePolicyNeverAddressesIsRejectedRatherThanSentCustomerText() {
        assertThatThrownBy(() -> resolver.resolve(
                event(NotificationEventType.JOB_STARTED, NotificationAudience.PROVIDER, Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JobStarted");
    }

    @Test
    void anAdminEditedChannelTextIsRenderedOnThatChannelOnly() {
        // Requirement 19.2: stored text overrides the built-in text for its channel; the other
        // channels, and the channel set, are unchanged.
        EventTemplateResolver edited = new EventTemplateResolver(id -> "JOB_STARTED.CUSTOMER.PUSH".equals(id)
                ? Optional.of(new TemplateText("Underway", "{{bookingReference}} is underway"))
                : Optional.empty());

        RenderedMessage message = edited.resolve(event(NotificationEventType.JOB_STARTED,
                NotificationAudience.CUSTOMER, Map.of("bookingReference", "HFX-3")));

        assertThat(message.channels()).containsExactlyInAnyOrder(NotificationChannel.PUSH, NotificationChannel.IN_APP);
        assertThat(message.titleFor(NotificationChannel.PUSH)).isEqualTo("Underway");
        assertThat(message.bodyFor(NotificationChannel.PUSH)).isEqualTo("HFX-3 is underway");
        assertThat(message.titleFor(NotificationChannel.IN_APP)).isEqualTo("Job started");
        assertThat(message.bodyFor(NotificationChannel.IN_APP)).isEqualTo("Work on HFX-3 has started.");
    }

    @Test
    void aStoredRowWithoutASubjectKeepsTheBuiltInHeading() {
        EventTemplateResolver edited = new EventTemplateResolver(
                id -> Optional.of(new TemplateText(null, "Custom body")));

        RenderedMessage message = edited.resolve(
                event(NotificationEventType.JOB_COMPLETED, NotificationAudience.CUSTOMER, Map.of()));

        assertThat(message.titleFor(NotificationChannel.EMAIL)).isEqualTo("Job completed");
        assertThat(message.bodyFor(NotificationChannel.EMAIL)).isEqualTo("Custom body");
    }

    @Test
    void aFailingTemplateStoreFallsBackToTheBuiltInText() {
        EventTemplateResolver failing = new EventTemplateResolver(id -> {
            throw new IllegalStateException("database down");
        });

        RenderedMessage message = failing.resolve(event(NotificationEventType.PROVIDER_ARRIVED,
                NotificationAudience.CUSTOMER, Map.of("bookingReference", "HFX-5")));

        assertThat(message.bodyFor(NotificationChannel.SMS)).isEqualTo("Your professional for HFX-5 has arrived.");
    }
}
