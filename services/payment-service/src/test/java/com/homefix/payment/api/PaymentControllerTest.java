package com.homefix.payment.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.payment.domain.PaymentMethod;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.domain.Settlement;
import com.homefix.payment.gateway.RazorpayGatewayAdapter;
import com.homefix.payment.service.GatewayCallback;
import com.homefix.payment.service.BookingPaymentService;
import com.homefix.payment.service.PayBookingCommand;
import com.homefix.payment.service.PaymentException;
import com.homefix.payment.service.PaymentService;

/**
 * Web-layer tests for {@link PaymentController} using standalone MockMvc so the controller,
 * request/response DTOs, and the shared {@link GlobalExceptionHandler} error envelope are
 * exercised end-to-end through JSON (de)serialization without a database, Redis, or Kafka
 * context (Requirement 12).
 *
 * <p>{@link PaymentController} now also enforces <em>ownership</em> through {@link CallerIdentity},
 * which reads the {@code SecurityContextHolder}; every test therefore has to authenticate a
 * principal. The cases that only exercise DTO/envelope plumbing authenticate a staff principal
 * (staff may act for any customer) so they stay focused on what they were written to assert; the
 * dedicated ownership tests below authenticate a plain CUSTOMER.
 */
@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock
    private PaymentService paymentService;
    @Mock
    private BookingPaymentService bookingPaymentService;

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new PaymentController(paymentService, bookingPaymentService, new CallerIdentity()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        // Default principal: staff, so the pre-existing DTO/envelope tests are unaffected by the
        // ownership check. Ownership tests below re-authenticate as the caller they need.
        authenticate(UUID.randomUUID(), "SUPPORT_AGENT");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(UUID callerId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(callerId.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private PaymentTransaction pending(UUID customerId, UUID bookingId, UUID providerId) {
        return PaymentTransaction.initiate(
                "cust:" + customerId + ":booking:" + bookingId,
                customerId, bookingId, providerId,
                new BigDecimal("100.00"), new BigDecimal("20.00"),
                PaymentMethod.UPI, RazorpayGatewayAdapter.GATEWAY_ID, null);
    }

    @Test
    void initiate_returns201WithTransactionBody_andPassesOnlyBookingMethodAndGateway() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        PaymentTransaction tx = pending(customerId, bookingId, providerId);
        when(bookingPaymentService.payForBooking(any(PayBookingCommand.class), any(), anyBoolean()))
                .thenReturn(tx);

        LinkedHashMap<String, Object> req = new LinkedHashMap<>();
        req.put("bookingId", bookingId);
        req.put("method", "UPI");
        req.put("gatewayId", RazorpayGatewayAdapter.GATEWAY_ID);

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(tx.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.providerNetEarning").value(80.00));
        // Default principal is staff: no caller id is needed, and the staff flag is passed on.
        verify(bookingPaymentService).payForBooking(
                new PayBookingCommand(bookingId, PaymentMethod.UPI, RazorpayGatewayAdapter.GATEWAY_ID, null),
                null, true);
    }

    /** Old clients still send customerId/providerId/amount/platformFee: accepted, never trusted. */
    @Test
    void initiate_legacyPricingFields_areAcceptedAndIgnored() throws Exception {
        UUID callerA = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        PaymentTransaction tx = pending(callerA, bookingId, UUID.randomUUID());
        when(bookingPaymentService.payForBooking(any(PayBookingCommand.class), any(), anyBoolean()))
                .thenReturn(tx);
        authenticate(callerA, "CUSTOMER");

        // Even values that used to be rejected (another customer, a 3-decimal amount) are ignored.
        String body = """
                {"customerId":"%s","bookingId":"%s","providerId":"%s","amount":"33.334",
                 "platformFee":"1.00","method":"CASH"}
                """.formatted(UUID.randomUUID(), bookingId, UUID.randomUUID());

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(tx.getId().toString()));
        verify(bookingPaymentService).payForBooking(
                new PayBookingCommand(bookingId, PaymentMethod.CASH, null, null), callerA, false);
    }

    @Test
    void initiate_missingMethod_returns400ValidationEnvelope() throws Exception {
        String body = """
                {"bookingId":"%s"}
                """.formatted(UUID.randomUUID());

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        verify(bookingPaymentService, never()).payForBooking(any(), any(), anyBoolean());
    }

    @Test
    void initiate_missingBookingId_returns400ValidationEnvelope() throws Exception {
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"UPI\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        verify(bookingPaymentService, never()).payForBooking(any(), any(), anyBoolean());
    }

    @Test
    void initiate_unknownGateway_surfacesDomainErrorEnvelope() throws Exception {
        when(bookingPaymentService.payForBooking(any(PayBookingCommand.class), any(), anyBoolean()))
                .thenThrow(PaymentException.validation("Unknown gateway: paypal"));

        String body = """
                {"bookingId":"%s","method":"UPI","gatewayId":"paypal"}
                """.formatted(UUID.randomUUID());

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void initiate_bookingServiceDown_returns503Envelope() throws Exception {
        when(bookingPaymentService.payForBooking(any(PayBookingCommand.class), any(), anyBoolean()))
                .thenThrow(new PaymentException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_SERVICE_UNAVAILABLE",
                        "down"));

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":\"" + UUID.randomUUID() + "\",\"method\":\"UPI\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_SERVICE_UNAVAILABLE"));
    }

    @Test
    void settle_returns201WithSettlementBody() throws Exception {
        UUID providerId = UUID.randomUUID();
        Settlement settlement = Settlement.initiate(providerId, new BigDecimal("500.00"), "v1:cipher");
        when(paymentService.initiateSettlement(eq(providerId), eq(new BigDecimal("500.00")),
                eq("ACCT-1"), eq(RazorpayGatewayAdapter.GATEWAY_ID))).thenReturn(settlement);

        String body = """
                {"providerId":"%s","amount":"500.00","bankAccountRef":"ACCT-1","gatewayId":"razorpay"}
                """.formatted(providerId);

        mvc.perform(post("/payments/settlements")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerId").value(providerId.toString()))
                .andExpect(jsonPath("$.amount").value(500.00))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    // ------------------------------------------------------------------ callback and refund wiring

    @Test
    void callback_forwardsOnlyTheSignedFields_andIgnoresLegacyUnsignedOutcome() throws Exception {
        PaymentTransaction tx = pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        GatewayCallback expected = new GatewayCallback("razorpay", "{\"signed\":true}", "abc123");
        when(paymentService.handleGatewayCallback(tx.getId(), expected)).thenReturn(tx);

        // A legacy client still sending the old unsigned outcome fields: they must have no effect.
        String body = """
                {"gatewayId":"razorpay","payload":"{\\"signed\\":true}","signature":"abc123",
                 "succeeded":false,"failureReason":"forged"}
                """;

        mvc.perform(post("/payments/callbacks/" + tx.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tx.getId().toString()));
        verify(paymentService).handleGatewayCallback(tx.getId(), expected);
    }

    @Test
    void callback_missingSignature_returns400() throws Exception {
        String body = """
                {"gatewayId":"razorpay","payload":"{}"}
                """;

        mvc.perform(post("/payments/callbacks/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void refund_forwardsAmountAndIdempotencyKey() throws Exception {
        PaymentTransaction tx = pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(paymentService.refund(tx.getId(), new BigDecimal("40.00"), "rf-1")).thenReturn(tx);

        mvc.perform(post("/payments/" + tx.getId() + "/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"40.00\",\"idempotencyKey\":\"rf-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tx.getId().toString()));
    }

    @Test
    void refund_amountWithMoreThanTwoDecimals_returns400() throws Exception {
        mvc.perform(post("/payments/" + UUID.randomUUID() + "/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"33.334\",\"idempotencyKey\":\"rf-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        verify(paymentService, never()).refund(any(), any(), any());
    }

    @Test
    void reconcile_forwardsTransactionAndRefundIds() throws Exception {
        PaymentTransaction tx = pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        UUID refundId = UUID.randomUUID();
        when(paymentService.reconcileRefund(tx.getId(), refundId)).thenReturn(tx);
        authenticate(UUID.randomUUID(), "FINANCE_ADMIN");

        mvc.perform(post("/payments/" + tx.getId() + "/refunds/" + refundId + "/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tx.getId().toString()));
    }

    @Test
    void refund_withoutIdempotencyKey_returns400() throws Exception {
        mvc.perform(post("/payments/" + UUID.randomUUID() + "/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"40.00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    // ------------------------------------------------------------------ ownership

    @Test
    void get_otherCustomersTransaction_isForbidden() throws Exception {
        UUID callerA = UUID.randomUUID();
        UUID callerB = UUID.randomUUID();
        PaymentTransaction tx = pending(callerB, UUID.randomUUID(), UUID.randomUUID());
        when(paymentService.getTransaction(tx.getId())).thenReturn(tx);
        authenticate(callerA, "CUSTOMER");

        mvc.perform(get("/payments/" + tx.getId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void get_ownTransaction_returnsTransaction() throws Exception {
        UUID callerA = UUID.randomUUID();
        PaymentTransaction tx = pending(callerA, UUID.randomUUID(), UUID.randomUUID());
        when(paymentService.getTransaction(tx.getId())).thenReturn(tx);
        authenticate(callerA, "CUSTOMER");

        mvc.perform(get("/payments/" + tx.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tx.getId().toString()))
                .andExpect(jsonPath("$.customerId").value(callerA.toString()));
    }

    @Test
    void get_anyTransaction_isAllowedForStaff() throws Exception {
        UUID staffId = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();
        PaymentTransaction tx = pending(someoneElse, UUID.randomUUID(), UUID.randomUUID());
        when(paymentService.getTransaction(tx.getId())).thenReturn(tx);
        authenticate(staffId, "FINANCE_ADMIN");

        mvc.perform(get("/payments/" + tx.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tx.getId().toString()));
    }

    /** Review finding: any customer could push another customer's PENDING payment towards FAILED. */
    @Test
    void retry_otherCustomersTransaction_isForbidden_andNotRecorded() throws Exception {
        UUID callerA = UUID.randomUUID();
        UUID callerB = UUID.randomUUID();
        PaymentTransaction tx = pending(callerB, UUID.randomUUID(), UUID.randomUUID());
        when(paymentService.getTransaction(tx.getId())).thenReturn(tx);
        authenticate(callerA, "CUSTOMER");

        mvc.perform(post("/payments/" + tx.getId() + "/retries").param("failureReason", "declined"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        verify(paymentService, never()).retryPayment(any(), any());
    }

    @Test
    void retry_ownTransaction_isRecorded() throws Exception {
        UUID callerA = UUID.randomUUID();
        PaymentTransaction tx = pending(callerA, UUID.randomUUID(), UUID.randomUUID());
        when(paymentService.getTransaction(tx.getId())).thenReturn(tx);
        when(paymentService.retryPayment(tx.getId(), "declined")).thenReturn(tx);
        authenticate(callerA, "CUSTOMER");

        mvc.perform(post("/payments/" + tx.getId() + "/retries").param("failureReason", "declined"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tx.getId().toString()));
    }

    /** A customer paying somebody else's booking gets the same 404 as for a missing booking. */
    @Test
    void initiate_otherCustomersBooking_is404() throws Exception {
        UUID callerA = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        authenticate(callerA, "CUSTOMER");
        when(bookingPaymentService.payForBooking(any(PayBookingCommand.class), eq(callerA), eq(false)))
                .thenThrow(PaymentException.bookingNotFound(bookingId));

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":\"" + bookingId + "\",\"method\":\"UPI\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_FOUND"));
    }

    @Test
    void initiate_notPayable_is409() throws Exception {
        UUID callerA = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        authenticate(callerA, "CUSTOMER");
        when(bookingPaymentService.payForBooking(any(PayBookingCommand.class), eq(callerA), eq(false)))
                .thenThrow(PaymentException.bookingNotPayable(bookingId));

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":\"" + bookingId + "\",\"method\":\"UPI\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_PAYABLE"));
    }

    @Test
    void initiate_forSelf_passesCallerIdentity_andReturns201() throws Exception {
        UUID callerA = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        PaymentTransaction tx = pending(callerA, bookingId, UUID.randomUUID());
        when(bookingPaymentService.payForBooking(any(PayBookingCommand.class), eq(callerA), eq(false)))
                .thenReturn(tx);
        authenticate(callerA, "CUSTOMER");

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":\"" + bookingId + "\",\"method\":\"UPI\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(tx.getId().toString()));
    }
}
