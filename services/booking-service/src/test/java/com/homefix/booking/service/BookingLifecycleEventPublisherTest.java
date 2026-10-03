package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.support.Bookings;
import com.homefix.shared.outbox.OutboxEventEntity;
import com.homefix.shared.outbox.OutboxEventPublisher;
import com.homefix.shared.outbox.OutboxEventRepository;

/**
 * Contract tests for the state-driven booking events (Requirement 22.1): each payload is
 * serialised through the real shared {@link OutboxEventPublisher}, exactly as the Outbox Processor
 * will relay it, and read back through mirrors of the consumers' own records.
 *
 * <p>The mirrors copy chat-service's and notification-service's {@code LifecycleEventPayload}
 * field for field. If either consumer adds a field these events must carry, update the mirror and
 * the event together.
 */
class BookingLifecycleEventPublisherTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-11T09:30:00Z"), ZoneOffset.UTC);

    /**
     * Same defaults as the Spring Boot ObjectMapper the publisher and consumers are given: Boot's
     * Jackson auto-configuration additionally turns off timestamp-style dates.
     */
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private OutboxEventRepository outboxRepository;
    private BookingLifecycleEventPublisher publisher;

    /** Mirror of chat-service {@code com.homefix.chat.consumer.LifecycleEventPayload}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatLifecycleEventPayload(UUID bookingId, UUID customerId, UUID providerId,
                                     Instant bookingCreatedAt) {
    }

    /** Mirror of notification-service {@code com.homefix.notification.consumer.LifecycleEventPayload}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record NotificationLifecycleEventPayload(UUID bookingId,
                                             @JsonAlias("bookingReference") String reference,
                                             UUID customerId, UUID providerId,
                                             UUID reviewerId, UUID revieweeId, UUID complaintId,
                                             String newStatus, String status) {
    }

    @BeforeEach
    void setUp() {
        outboxRepository = mock(OutboxEventRepository.class);
        publisher = new BookingLifecycleEventPublisher(
                new OutboxEventPublisher(outboxRepository, objectMapper), CLOCK);
    }

    @Test
    void bookingCancelledCarriesWhatChatNeedsToCloseTheChannel() throws Exception {
        Booking booking = Bookings.inState(BookingStatus.CANCELLED);
        booking.setProviderId(UUID.randomUUID());

        publisher.onTransition(booking, BookingStatus.PROVIDER_ACCEPTED, BookingStatus.CANCELLED,
                Actor.user(booking.getCustomerId(), "CUSTOMER"), "changed my mind");

        OutboxEventEntity row = savedRow();
        ChatLifecycleEventPayload chat = objectMapper.readValue(row.getPayload(), ChatLifecycleEventPayload.class);
        // chat-service rejects a BookingCancelled without bookingId as poison.
        assertThat(chat.bookingId()).isEqualTo(booking.getId());
        assertThat(chat.customerId()).isEqualTo(booking.getCustomerId());
        assertThat(chat.providerId()).isEqualTo(booking.getProviderId());
        assertThat(chat.bookingCreatedAt()).isEqualTo(booking.getCreatedAt());
    }

    @Test
    void bookingCancelledResolvesANotificationRecipient() throws Exception {
        Booking booking = Bookings.inState(BookingStatus.CANCELLED);

        publisher.onTransition(booking, BookingStatus.SEARCHING_PROVIDER, BookingStatus.CANCELLED,
                Actor.user(booking.getCustomerId(), "CUSTOMER"), null);

        NotificationLifecycleEventPayload notification = objectMapper.readValue(
                savedRow().getPayload(), NotificationLifecycleEventPayload.class);
        assertThat(notification.bookingId()).isEqualTo(booking.getId());
        assertThat(notification.customerId()).isEqualTo(booking.getCustomerId());
        assertThat(notification.reference()).isEqualTo(booking.getReference());
        // Cancelled before assignment: there is no provider yet.
        assertThat(notification.providerId()).isNull();
        // Surfaced to templates as the bookingStatus attribute.
        assertThat(notification.status()).isEqualTo("CANCELLED");
    }

    @Test
    void bookingCancelledWireShape() throws Exception {
        Booking booking = Bookings.inState(BookingStatus.CANCELLED);
        UUID providerId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        booking.setProviderId(providerId);
        booking.setCancellationFee(new BigDecimal("75.00"));

        publisher.onTransition(booking, BookingStatus.PROVIDER_ON_THE_WAY, BookingStatus.CANCELLED,
                Actor.user(adminId, "ADMIN"), "provider unreachable");

        OutboxEventEntity row = savedRow();
        assertThat(row.getAggregateType()).isEqualTo("Booking");
        assertThat(row.getAggregateId()).isEqualTo(booking.getId());
        assertThat(row.getEventType()).isEqualTo("BookingCancelled");

        JsonNode json = objectMapper.readTree(row.getPayload());
        assertThat(json.get("bookingId").asText()).isEqualTo(booking.getId().toString());
        assertThat(json.get("reference").asText()).isEqualTo(booking.getReference());
        assertThat(json.has("bookingReference")).isFalse();
        assertThat(json.get("customerId").asText()).isEqualTo(booking.getCustomerId().toString());
        assertThat(json.get("providerId").asText()).isEqualTo(providerId.toString());
        assertThat(json.get("previousStatus").asText()).isEqualTo("PROVIDER_ON_THE_WAY");
        assertThat(json.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(json.get("cancelledBy").asText()).isEqualTo(adminId.toString());
        assertThat(json.get("cancelledByRole").asText()).isEqualTo("ADMIN");
        assertThat(json.get("reason").asText()).isEqualTo("provider unreachable");
        assertThat(json.get("cancellationFee").decimalValue()).isEqualByComparingTo("75.00");
        // ISO-8601 strings, not epoch numbers, so every consumer reads them as Instants.
        assertThat(json.get("bookingCreatedAt").isTextual()).isTrue();
        assertThat(json.get("occurredAt").asText()).isEqualTo("2026-09-11T09:30:00Z");
    }

    @Test
    void providerAssignedNamesTheCustomerAndTheProvider() throws Exception {
        Booking booking = Bookings.inState(BookingStatus.PROVIDER_ASSIGNED);
        UUID providerId = UUID.randomUUID();
        booking.setProviderId(providerId);

        publisher.onTransition(booking, BookingStatus.SEARCHING_PROVIDER, BookingStatus.PROVIDER_ASSIGNED,
                Actor.user(providerId, "dispatch-engine"), "assigned");

        OutboxEventEntity row = savedRow();
        assertThat(row.getAggregateType()).isEqualTo("Booking");
        assertThat(row.getAggregateId()).isEqualTo(booking.getId());
        assertThat(row.getEventType()).isEqualTo("ProviderAssigned");

        NotificationLifecycleEventPayload notification = objectMapper.readValue(
                row.getPayload(), NotificationLifecycleEventPayload.class);
        assertThat(notification.bookingId()).isEqualTo(booking.getId());
        assertThat(notification.customerId()).isEqualTo(booking.getCustomerId());
        assertThat(notification.providerId()).isEqualTo(providerId);
        assertThat(notification.reference()).isEqualTo(booking.getReference());

        JsonNode json = objectMapper.readTree(row.getPayload());
        assertThat(json.get("reference").asText()).isEqualTo(booking.getReference());
        assertThat(json.get("bookingCreatedAt").isTextual()).isTrue();
        assertThat(json.get("occurredAt").asText()).isEqualTo("2026-09-11T09:30:00Z");
    }

    @Test
    void providerAssignedByATenantNamesTheTenant() throws Exception {
        Booking booking = Bookings.inState(BookingStatus.PROVIDER_ASSIGNED);
        UUID tenantId = UUID.randomUUID();
        booking.setProviderId(UUID.randomUUID());
        booking.setTenantId(tenantId);

        publisher.onTransition(booking, BookingStatus.AWAITING_ASSIGNMENT, BookingStatus.PROVIDER_ASSIGNED,
                Actor.user(UUID.randomUUID(), "TENANT_ADMIN"), "assigned", "Ara Home Services");

        // Requirement MT-5.3: the provider's notification says which agency assigned the job.
        JsonNode json = objectMapper.readTree(savedRow().getPayload());
        assertThat(json.get("tenantId").asText()).isEqualTo(tenantId.toString());
        assertThat(json.get("tenantName").asText()).isEqualTo("Ara Home Services");
        // Still readable by the consumers' existing records.
        assertThat(objectMapper.readValue(savedRow().getPayload(), NotificationLifecycleEventPayload.class)
                .customerId()).isEqualTo(booking.getCustomerId());
    }

    @Test
    void providerAssignedWithoutATenantSendsNullTenantFields() throws Exception {
        Booking booking = Bookings.inState(BookingStatus.PROVIDER_ASSIGNED);
        booking.setProviderId(UUID.randomUUID());

        publisher.onTransition(booking, BookingStatus.SEARCHING_PROVIDER, BookingStatus.PROVIDER_ASSIGNED,
                Actor.system(), "assigned", "ignored without a tenant");

        JsonNode json = objectMapper.readTree(savedRow().getPayload());
        assertThat(json.get("tenantId").isNull()).isTrue();
        assertThat(json.get("tenantName").isNull()).isTrue();
    }

    @Test
    void enteringTheAssignmentQueueWritesNoRow() {
        // Requirement MT-4.2: the fallback is not a cancellation, and a decline is not news.
        Booking booking = Bookings.inState(BookingStatus.AWAITING_ASSIGNMENT);

        publisher.onTransition(booking, BookingStatus.SEARCHING_PROVIDER, BookingStatus.AWAITING_ASSIGNMENT,
                Actor.system(), "routed");
        publisher.onTransition(booking, BookingStatus.PROVIDER_ASSIGNED, BookingStatus.AWAITING_ASSIGNMENT,
                Actor.system(), "declined");

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void otherTargetsWriteNoRow() {
        Booking booking = Bookings.inState(BookingStatus.PROVIDER_ACCEPTED);

        publisher.onTransition(booking, BookingStatus.PROVIDER_ASSIGNED, BookingStatus.PROVIDER_ACCEPTED,
                Actor.system(), null);

        verify(outboxRepository, never()).save(any());
    }

    private OutboxEventEntity savedRow() {
        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxRepository).save(captor.capture());
        return captor.getValue();
    }
}
