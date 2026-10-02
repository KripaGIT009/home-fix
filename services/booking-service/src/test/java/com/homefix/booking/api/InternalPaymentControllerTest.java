package com.homefix.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import com.homefix.booking.config.InternalApiKeyFilter;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.service.BookingLifecycleEventPublisher;
import com.homefix.booking.service.BookingPaymentService;
import com.homefix.booking.service.BookingTransitionService;
import com.homefix.booking.support.InMemoryBookingAuditRepository;
import com.homefix.booking.support.InMemoryBookingRepository;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * Web-layer tests for {@link InternalPaymentController}: the contract the Payment Service reads
 * before charging a booking (Requirement 12.1).
 *
 * <p>The controller runs behind the real {@link InternalApiKeyFilter}, so the tests also pin that
 * these paths are refused without the internal credential; the service, state machine and audit
 * trail behind it are real, over in-memory persistence.
 */
class InternalPaymentControllerTest {

    private static final String KEY = "internal-test-key";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T09:30:00Z"), ZoneOffset.UTC);
    private static final UUID CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROVIDER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private InMemoryBookingRepository repository;
    private InMemoryBookingAuditRepository audits;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = new InMemoryBookingRepository();
        audits = new InMemoryBookingAuditRepository();
        BookingPaymentService service = new BookingPaymentService(repository,
                new BookingTransitionService(new BookingStateMachine(), audits,
                        new BookingLifecycleEventPublisher(mock(OutboxEventPublisher.class), CLOCK), CLOCK),
                mock(PlatformTransactionManager.class));
        mvc = MockMvcBuilders.standaloneSetup(new InternalPaymentController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new InternalApiKeyFilter(KEY))
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Booking book(BookingStatus status, BigDecimal estimated, BigDecimal finalTotal) {
        Booking b = Booking.create("HFX-20261003-PAY001", CUSTOMER, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), false, Instant.now(CLOCK), estimated);
        b.setProviderId(PROVIDER);
        b.setFinalTotal(finalTotal);
        b.applyStatus(status);
        return repository.save(b);
    }

    private Booking book(BookingStatus status) {
        return book(status, new BigDecimal("100.00"), null);
    }

    private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return request.header(InternalApiKeyFilter.HEADER, KEY);
    }

    private static MockHttpServletRequestBuilder pending(UUID bookingId, UUID customerId) {
        return internal(post("/internal/bookings/{id}/payment-pending", bookingId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + customerId + "\"}"));
    }

    // ----- GET payment-facts -----

    @Test
    void paymentFacts_returnsWhoPaysWhomAndHowMuch() throws Exception {
        Booking b = book(BookingStatus.JOB_COMPLETED, new BigDecimal("100.00"), new BigDecimal("1250.5"));

        mvc.perform(internal(get("/internal/bookings/{id}/payment-facts", b.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(b.getId().toString()))
                .andExpect(jsonPath("$.reference").value("HFX-20261003-PAY001"))
                .andExpect(jsonPath("$.customerId").value(CUSTOMER.toString()))
                .andExpect(jsonPath("$.providerId").value(PROVIDER.toString()))
                .andExpect(jsonPath("$.status").value("JOB_COMPLETED"))
                .andExpect(jsonPath("$.currency").value("INR"))
                // The final total wins over the estimate, at two decimal places.
                .andExpect(content().string(containsString("\"amount\":1250.50")));
    }

    @Test
    void paymentFacts_fallsBackToTheEstimate() throws Exception {
        Booking b = book(BookingStatus.JOB_COMPLETED, new BigDecimal("899"), null);

        mvc.perform(internal(get("/internal/bookings/{id}/payment-facts", b.getId())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"amount\":899.00")));
    }

    @Test
    void paymentFacts_unknownBookingIs404() throws Exception {
        mvc.perform(internal(get("/internal/bookings/{id}/payment-facts", UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_FOUND"));
    }

    @Test
    void paymentFacts_withoutTheInternalKeyIsRefused() throws Exception {
        Booking b = book(BookingStatus.JOB_COMPLETED);

        mvc.perform(get("/internal/bookings/{id}/payment-facts", b.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_AUTH_FAILED"));
        mvc.perform(get("/internal/bookings/{id}/payment-facts", b.getId())
                        .header(InternalApiKeyFilter.HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    // ----- POST payment-pending -----

    @Test
    void pending_movesACompletedJobToPaymentPendingAndReturnsTheFacts() throws Exception {
        Booking b = book(BookingStatus.JOB_COMPLETED);

        mvc.perform(pending(b.getId(), CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(b.getId().toString()))
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.providerId").value(PROVIDER.toString()))
                .andExpect(content().string(containsString("\"amount\":100.00")));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.PAYMENT_PENDING);
        assertThat(audits.byBooking(b.getId())).hasSize(2);
    }

    @Test
    void pending_fromCustomerConfirmed() throws Exception {
        Booking b = book(BookingStatus.CUSTOMER_CONFIRMED);

        mvc.perform(pending(b.getId(), CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));
    }

    @Test
    void pending_isIdempotent() throws Exception {
        Booking b = book(BookingStatus.JOB_COMPLETED);

        mvc.perform(pending(b.getId(), CUSTOMER)).andExpect(status().isOk());
        mvc.perform(pending(b.getId(), CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));

        assertThat(audits.byBooking(b.getId())).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {
            "JOB_STARTED", "CUSTOMER_APPROVAL_PENDING", "PAYMENT_COMPLETED", "DISPUTED", "REFUNDED", "CANCELLED"})
    void pending_fromAStateThatIsNotPayableIs409(BookingStatus status) throws Exception {
        Booking b = book(status);

        mvc.perform(pending(b.getId(), CUSTOMER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_PAYABLE"));
        assertThat(b.getStatus()).isEqualTo(status);
    }

    @Test
    void pending_forSomeoneElsesBookingIsTheSame404AsAMissingOne() throws Exception {
        Booking b = book(BookingStatus.JOB_COMPLETED);

        mvc.perform(pending(b.getId(), UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_FOUND"));
        mvc.perform(pending(UUID.randomUUID(), CUSTOMER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_FOUND"));
        assertThat(b.getStatus()).isEqualTo(BookingStatus.JOB_COMPLETED);
    }

    @Test
    void pending_withoutCustomerIdIs400() throws Exception {
        Booking b = book(BookingStatus.JOB_COMPLETED);

        mvc.perform(internal(post("/internal/bookings/{id}/payment-pending", b.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void pending_withoutTheInternalKeyIsRefusedAndChangesNothing() throws Exception {
        Booking b = book(BookingStatus.JOB_COMPLETED);

        mvc.perform(post("/internal/bookings/{id}/payment-pending", b.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + CUSTOMER + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_AUTH_FAILED"));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.JOB_COMPLETED);
        assertThat(audits.all()).isEmpty();
    }
}
