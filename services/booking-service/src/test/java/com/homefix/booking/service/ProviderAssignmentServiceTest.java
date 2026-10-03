package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingAudit;
import com.homefix.booking.domain.BookingAuditRepository;
import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.support.Bookings;
import com.homefix.booking.support.InMemoryBookingRepository;
import com.homefix.shared.outbox.OutboxEventEntity;
import com.homefix.shared.outbox.OutboxEventPublisher;
import com.homefix.shared.outbox.OutboxEventRepository;

/**
 * The assigned Provider's answer to a Tenant assignment (Requirement MT-6): acceptance moves the job
 * on and writes a {@code ProviderAccepted} indistinguishable from the Dispatch Engine's (Property
 * MT7), a decline returns it to the Tenant's queue keeping the Tenant and the queue time
 * (Requirement MT-6.2, MT-7.2), and nobody but the assigned Provider may answer (Requirement MT-6.3).
 *
 * <p>The event is serialised through the real shared {@link OutboxEventPublisher}, as the Outbox
 * Processor relays it, and compared with the Dispatch Engine's field list and read back through
 * chat-service's consumer record.
 */
class ProviderAssignmentServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T10:15:00Z"), ZoneOffset.UTC);

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    /** Mirror of chat-service {@code com.homefix.chat.consumer.LifecycleEventPayload}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatLifecycleEventPayload(UUID bookingId, UUID customerId, UUID providerId,
                                     Instant bookingCreatedAt) {
    }

    private InMemoryBookingRepository repository;
    private OutboxEventRepository outboxRepository;
    private BookingAuditRepository auditRepository;
    private final List<BookingAudit> audits = new ArrayList<>();
    private ProviderAssignmentService service;

    private final UUID providerId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final Instant queuedAt = Instant.parse("2026-10-03T09:40:00Z");

    @BeforeEach
    void setUp() {
        repository = new InMemoryBookingRepository();
        outboxRepository = mock(OutboxEventRepository.class);
        auditRepository = mock(BookingAuditRepository.class);
        when(auditRepository.save(any())).thenAnswer(inv -> {
            audits.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        OutboxEventPublisher outbox = new OutboxEventPublisher(outboxRepository, objectMapper);
        BookingTransitionService transitions = new BookingTransitionService(new BookingStateMachine(),
                auditRepository, new BookingLifecycleEventPublisher(outbox, CLOCK), CLOCK);
        service = new ProviderAssignmentService(repository, transitions, outbox, CLOCK);
    }

    /** A booking a Tenant assigned to {@link #providerId} after the fallback. */
    private Booking tenantAssigned() {
        Booking booking = Bookings.inState(BookingStatus.PROVIDER_ASSIGNED);
        booking.setProviderId(providerId);
        booking.setTenantId(tenantId);
        booking.setQueuedForAssignmentAt(queuedAt);
        return repository.save(booking);
    }

    private OutboxEventEntity savedRow() {
        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxRepository).save(captor.capture());
        return captor.getValue();
    }

    // ----- acceptance (Requirement MT-6.1, Property MT7) -----------------------

    @Test
    void acceptanceMovesToProviderAcceptedAndWritesTheDispatchEnginesPayload() throws Exception {
        Booking booking = tenantAssigned();

        Booking result = service.accept(booking.getReference(), providerId);

        assertThat(result.getStatus()).isEqualTo(BookingStatus.PROVIDER_ACCEPTED);
        assertThat(result.getTenantId()).isEqualTo(tenantId);
        OutboxEventEntity row = savedRow();
        assertThat(row.getEventType()).isEqualTo("ProviderAccepted");
        assertThat(row.getAggregateType()).isEqualTo("Booking");
        assertThat(row.getAggregateId()).isEqualTo(booking.getId());

        JsonNode json = objectMapper.readTree(row.getPayload());
        // Exactly the fields of dispatch-engine's ProviderAcceptedEvent, nothing more.
        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("bookingId", "customerId", "providerId", "bookingCreatedAt", "acceptedAt");
        assertThat(json.get("acceptedAt").asText()).isEqualTo("2026-10-03T10:15:00Z");

        ChatLifecycleEventPayload chat = objectMapper.readValue(row.getPayload(), ChatLifecycleEventPayload.class);
        assertThat(chat.bookingId()).isEqualTo(booking.getId());
        assertThat(chat.customerId()).isEqualTo(booking.getCustomerId());
        assertThat(chat.providerId()).isEqualTo(providerId);
        assertThat(chat.bookingCreatedAt()).isEqualTo(booking.getCreatedAt());

        assertThat(audits).extracting(BookingAudit::getToState).containsExactly(BookingStatus.PROVIDER_ACCEPTED);
        assertThat(audits.get(0).getActorId()).isEqualTo(providerId);
    }

    @Test
    void repeatingTheAcceptanceIsANoOp() {
        Booking booking = tenantAssigned();
        service.accept(booking.getId().toString(), providerId);

        Booking again = service.accept(booking.getId().toString(), providerId);

        assertThat(again.getStatus()).isEqualTo(BookingStatus.PROVIDER_ACCEPTED);
        verify(outboxRepository, times(1)).save(any());
    }

    @Test
    void anyoneButTheAssignedProviderGets404() {
        Booking booking = tenantAssigned();

        for (UUID stranger : List.of(UUID.randomUUID(), booking.getCustomerId())) {
            assertThatThrownBy(() -> service.accept(booking.getReference(), stranger))
                    .isInstanceOf(BookingException.class)
                    .satisfies(e -> assertThat(((BookingException) e).getErrorCode()).isEqualTo("BOOKING_NOT_FOUND"));
            assertThatThrownBy(() -> service.decline(booking.getReference(), stranger))
                    .satisfies(e -> assertThat(((BookingException) e).getErrorCode()).isEqualTo("BOOKING_NOT_FOUND"));
        }
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PROVIDER_ASSIGNED);
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void acceptingFromAnotherStateIs409() {
        Booking booking = tenantAssigned();
        booking.applyStatus(BookingStatus.PROVIDER_ON_THE_WAY);

        assertThatThrownBy(() -> service.accept(booking.getReference(), providerId))
                .isInstanceOf(InvalidTransitionException.class);
        verify(outboxRepository, never()).save(any());
    }

    // ----- decline (Requirement MT-6.2, MT-7.2) --------------------------------

    @Test
    void declineReturnsTheBookingToTheTenantsQueueKeepingTheQueueTime() {
        Booking booking = tenantAssigned();

        Booking result = service.decline(booking.getReference(), providerId);

        assertThat(result.getStatus()).isEqualTo(BookingStatus.AWAITING_ASSIGNMENT);
        assertThat(result.getProviderId()).isNull();
        assertThat(result.getTenantId()).isEqualTo(tenantId);
        assertThat(result.getQueuedForAssignmentAt()).isEqualTo(queuedAt);
        assertThat(audits).extracting(BookingAudit::getToState).containsExactly(BookingStatus.AWAITING_ASSIGNMENT);
        // Neither a cancellation nor an assignment: no event.
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void anAutomaticallyDispatchedJobCannotBeDeclinedIntoAQueue() {
        Booking booking = Bookings.inState(BookingStatus.PROVIDER_ASSIGNED);
        booking.setProviderId(providerId);
        repository.save(booking);

        assertThatThrownBy(() -> service.decline(booking.getReference(), providerId))
                .isInstanceOf(InvalidTransitionException.class);
        assertThat(booking.getProviderId()).isEqualTo(providerId);
    }

    @Test
    void decliningAnAcceptedJobIs409() {
        Booking booking = tenantAssigned();
        service.accept(booking.getReference(), providerId);

        assertThatThrownBy(() -> service.decline(booking.getReference(), providerId))
                .isInstanceOf(InvalidTransitionException.class);
        assertThat(booking.getProviderId()).isEqualTo(providerId);
    }
}
