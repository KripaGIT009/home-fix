package com.homefix.dispatch.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.homefix.dispatch.adapter.HttpBookingTransitionAdapter.BookingTransitionException;
import com.homefix.dispatch.domain.BookingNotSearchableException;
import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.EnrichmentUnavailableException;
import com.homefix.dispatch.domain.UnresolvableBookingException;
import com.homefix.dispatch.event.BookingCreatedEvent;
import com.homefix.dispatch.port.BookingTransitionPort;
import com.homefix.dispatch.port.CustomerAddressPort;
import com.homefix.dispatch.port.CustomerAddressPort.ResolvedAddress;
import com.homefix.dispatch.port.SubcategorySkillsPort;
import com.homefix.dispatch.service.BookingEnrichmentService;
import com.homefix.dispatch.service.BulkheadDispatchExecutor;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import com.homefix.shared.outbox.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * Tests {@link BookingCreatedConsumer} with the shared consumer base's real retry and
 * dead-letter logic and the real {@link BookingEnrichmentService}, with only the two outbound ports
 * mocked (Requirements 8.2, 22.5, 22.6):
 * <ul>
 *   <li>the producer's actual shape (addressId and subcategoryId, no coordinates or skills) is
 *       enriched and dispatched on the resolved values;</li>
 *   <li>an unavailable dependency goes through the retry path and, if it stays down, is
 *       dead-lettered without being recorded as processed, so it can be replayed;</li>
 *   <li>a confirmed-missing address, a missing addressId, a foreign address, or a subcategory with
 *       no skill tags is refused: the booking is moved to SEARCHING_FAILED rather than left
 *       searching forever, and the event is consumed, not dead-lettered;</li>
 *   <li>duplicates are skipped and unparseable payloads dead-lettered, as before.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BookingCreatedConsumerTest {

    private static final String TOPIC = "BookingCreated";

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private DlqForwarder dlqForwarder;
    @Mock
    private BulkheadDispatchExecutor executor;
    @Mock
    private CustomerAddressPort customerAddress;
    @Mock
    private SubcategorySkillsPort subcategorySkills;
    @Mock
    private BookingTransitionPort bookingTransition;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private BookingCreatedConsumer consumer;

    private final UUID bookingId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID subcategoryId = UUID.randomUUID();
    private final UUID addressId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        BookingEnrichmentService enrichment = new BookingEnrichmentService(customerAddress, subcategorySkills);
        consumer = new BookingCreatedConsumer(processedEventRepository, dlqForwarder, mapper,
                enrichment, executor, bookingTransition);
    }

    private ConsumerRecord<String, String> record(UUID eventId, String value) {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>(TOPIC, 0, 0L, "key", value);
        rec.headers().add(new RecordHeader(KafkaProducerTemplate.HEADER_EVENT_ID,
                eventId.toString().getBytes(StandardCharsets.UTF_8)));
        return rec;
    }

    /** A record as the listener container delivers it, stamped with its delivery attempt. */
    private ConsumerRecord<String, String> containerRecord(UUID eventId, String value, int attempt) {
        ConsumerRecord<String, String> rec = record(eventId, value);
        rec.headers().add(new RecordHeader(KafkaHeaders.DELIVERY_ATTEMPT,
                ByteBuffer.allocate(Integer.BYTES).putInt(attempt).array()));
        return rec;
    }

    /** The shape the Booking Service actually publishes: booking facts only. */
    private String producerShape(UUID address) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("bookingId", bookingId);
        body.put("reference", "HFX-2026-0004821");
        body.put("customerId", customerId);
        body.put("categoryId", UUID.randomUUID());
        body.put("subcategoryId", subcategoryId);
        body.put("addressId", address);
        body.put("emergency", true);
        body.put("occurredAt", Instant.now().toString());
        return mapper.writeValueAsString(body);
    }

    private UUID newEvent() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), eq(eventId)))
                .thenReturn(false);
        return eventId;
    }

    private String capturedDeadLetterReason(UUID eventId, String payload) {
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(dlqForwarder).forward(eq(TOPIC), eq("key"), eq(eventId.toString()), eq(payload),
                reason.capture());
        return reason.getValue();
    }

    // ---- enrichment success --------------------------------------------------------------------

    @Test
    void producerShapedEvent_isEnrichedFromCustomerAndCatalogThenDispatched() throws Exception {
        UUID eventId = newEvent();
        when(customerAddress.lookup(addressId))
                .thenReturn(new ResolvedAddress(addressId, customerId, 25.5941, 85.1376));
        when(subcategorySkills.requiredSkillTags(subcategoryId)).thenReturn(List.of("plumbing"));

        consumer.onMessage(record(eventId, producerShape(addressId)));

        ArgumentCaptor<DispatchRequest> captor = ArgumentCaptor.forClass(DispatchRequest.class);
        verify(executor).submit(captor.capture());
        DispatchRequest request = captor.getValue();
        assertThat(request.bookingId()).isEqualTo(bookingId);
        assertThat(request.customerId()).isEqualTo(customerId);
        assertThat(request.customerLat()).isEqualTo(25.5941);
        assertThat(request.customerLon()).isEqualTo(85.1376);
        assertThat(request.requiredSkillTags()).containsExactly("plumbing");
        assertThat(request.emergency()).isTrue();
        verify(processedEventRepository).save(any());
        verifyNoInteractions(dlqForwarder);
    }

    @Test
    void eventAlreadyCarryingCoordinatesAndSkills_isDispatchedWithoutLookups() throws Exception {
        UUID eventId = newEvent();
        BookingCreatedEvent event = new BookingCreatedEvent(bookingId, customerId, subcategoryId,
                addressId, 12.9, 77.6, List.of("plumbing"), true, Instant.now());

        consumer.onMessage(record(eventId, mapper.writeValueAsString(event)));

        ArgumentCaptor<DispatchRequest> captor = ArgumentCaptor.forClass(DispatchRequest.class);
        verify(executor).submit(captor.capture());
        assertThat(captor.getValue().emergency()).isTrue();
        assertThat(captor.getValue().customerLat()).isEqualTo(12.9);
        verifyNoInteractions(customerAddress, subcategorySkills);
    }

    // ---- transient failure -> retry / DLT ------------------------------------------------------

    @Test
    void transientLookupFailure_thenSuccess_isRetriedAndDispatchedOnce() throws Exception {
        UUID eventId = newEvent();
        when(customerAddress.lookup(addressId))
                .thenThrow(new EnrichmentUnavailableException("Customer Service address lookup unavailable", null))
                .thenReturn(new ResolvedAddress(addressId, customerId, 25.5941, 85.1376));
        when(subcategorySkills.requiredSkillTags(subcategoryId)).thenReturn(List.of("plumbing"));

        consumer.onMessage(record(eventId, producerShape(addressId)));

        verify(customerAddress, times(2)).lookup(addressId);
        verify(executor, times(1)).submit(any());
        verify(processedEventRepository).save(any());
        verifyNoInteractions(dlqForwarder);
    }

    @Test
    void persistentTransientFailure_isDeadLetteredAfterRetriesAndNotRecordedSoItCanBeReplayed()
            throws Exception {
        UUID eventId = newEvent();
        String payload = producerShape(addressId);
        when(customerAddress.lookup(addressId)).thenThrow(new EnrichmentUnavailableException(
                "Customer Service address lookup unavailable: timed out", null));

        consumer.onMessage(record(eventId, payload));

        verify(customerAddress, times(3)).lookup(addressId);
        verify(executor, never()).submit(any());
        assertThat(capturedDeadLetterReason(eventId, payload))
                .contains("EnrichmentUnavailableException")
                .contains("Customer Service address lookup unavailable");
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void transientFailureUnderTheListenerContainer_isRethrownForRedeliveryNotDeadLettered()
            throws Exception {
        UUID eventId = newEvent();
        when(subcategorySkills.requiredSkillTags(subcategoryId)).thenThrow(
                new EnrichmentUnavailableException("Service Catalog skill-tag lookup unavailable", null));
        when(customerAddress.lookup(addressId))
                .thenReturn(new ResolvedAddress(addressId, customerId, 25.5941, 85.1376));

        // Attempt 1 of 3: the container re-seeks and pauses for the retry delay.
        assertThatThrownBy(() -> consumer.onMessage(containerRecord(eventId, producerShape(addressId), 1)))
                .isInstanceOf(EnrichmentUnavailableException.class);

        verify(executor, never()).submit(any());
        verifyNoInteractions(dlqForwarder);
        verify(processedEventRepository, never()).save(any());
    }

    // ---- permanent failure -> SEARCHING_FAILED, event consumed ----------------------------------

    /** The booking was failed, nothing was dispatched, and the event was consumed, not dead-lettered. */
    private void assertFailedNotDeadLettered() {
        verify(bookingTransition).markSearchingFailed(bookingId);
        verify(executor, never()).submit(any());
        verifyNoInteractions(dlqForwarder);
        verify(processedEventRepository).save(any());
    }

    @Test
    void addressNotFound_marksBookingSearchingFailed() throws Exception {
        UUID eventId = newEvent();
        when(customerAddress.lookup(addressId)).thenThrow(new UnresolvableBookingException(
                "address " + addressId + " does not exist in the Customer Service (ADDRESS_NOT_FOUND)"));

        consumer.onMessage(record(eventId, producerShape(addressId)));

        assertFailedNotDeadLettered();
    }

    @Test
    void eventWithoutCoordinatesOrAddressId_isFailedWithoutAnyLookup() throws Exception {
        UUID eventId = newEvent();

        consumer.onMessage(record(eventId, producerShape(null)));

        verifyNoInteractions(customerAddress);
        assertFailedNotDeadLettered();
    }

    @Test
    void addressOwnedByAnotherCustomer_isFailed() throws Exception {
        UUID eventId = newEvent();
        when(customerAddress.lookup(addressId))
                .thenReturn(new ResolvedAddress(addressId, UUID.randomUUID(), 25.5941, 85.1376));

        consumer.onMessage(record(eventId, producerShape(addressId)));

        assertFailedNotDeadLettered();
    }

    @Test
    void subcategoryWithNoSkillTagsInTheCatalog_isFailed() throws Exception {
        UUID eventId = newEvent();
        when(customerAddress.lookup(addressId))
                .thenReturn(new ResolvedAddress(addressId, customerId, 25.5941, 85.1376));
        when(subcategorySkills.requiredSkillTags(subcategoryId)).thenReturn(List.of());

        consumer.onMessage(record(eventId, producerShape(addressId)));

        assertFailedNotDeadLettered();
    }

    @Test
    void subcategoryMissingFromTheCatalog_isFailed() throws Exception {
        UUID eventId = newEvent();
        when(customerAddress.lookup(addressId))
                .thenReturn(new ResolvedAddress(addressId, customerId, 25.5941, 85.1376));
        when(subcategorySkills.requiredSkillTags(subcategoryId)).thenThrow(new UnresolvableBookingException(
                "subcategory " + subcategoryId + " is not in the active Service Catalog (unknown or deactivated)"));

        consumer.onMessage(record(eventId, producerShape(addressId)));

        assertFailedNotDeadLettered();
    }

    @Test
    void unresolvableBookingAlreadyCancelled_isConsumedQuietly() throws Exception {
        UUID eventId = newEvent();
        doThrow(new BookingNotSearchableException(bookingId, "409", null))
                .when(bookingTransition).markSearchingFailed(bookingId);

        consumer.onMessage(record(eventId, producerShape(null)));

        assertFailedNotDeadLettered();
    }

    @Test
    void unresolvableBookingWhileBookingServiceIsDown_isRetriedThenDeadLettered() throws Exception {
        UUID eventId = newEvent();
        String payload = producerShape(null);
        doThrow(new BookingTransitionException("Booking Service transition unavailable (degraded)", null))
                .when(bookingTransition).markSearchingFailed(bookingId);

        consumer.onMessage(record(eventId, payload));

        verify(bookingTransition, times(3)).markSearchingFailed(bookingId);
        assertThat(capturedDeadLetterReason(eventId, payload)).contains("BookingTransitionException");
        verify(processedEventRepository, never()).save(any());
    }

    // ---- unchanged behaviour -------------------------------------------------------------------

    @Test
    void duplicateEventId_isSkipped() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), eq(eventId)))
                .thenReturn(true);

        consumer.onMessage(record(eventId, "{}"));

        verify(executor, never()).submit(any());
        verifyNoInteractions(customerAddress, subcategorySkills);
    }

    @Test
    void unparseablePayload_isDeadLettered() {
        UUID eventId = newEvent();

        consumer.onMessage(record(eventId, "not-json"));

        verify(executor, never()).submit(any());
        verify(dlqForwarder).forward(eq(TOPIC), eq("key"), eq(eventId.toString()), eq("not-json"), anyString());
    }
}
