package com.homefix.payment.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.homefix.payment.domain.PaymentMethod;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.domain.TransactionStatus;
import com.homefix.payment.gateway.RazorpayGatewayAdapter;
import com.homefix.payment.service.AdminPaymentQueryService;
import com.homefix.payment.service.PaymentException;
import com.homefix.payment.service.PaymentService;
import com.homefix.payment.support.InMemoryPaymentTransactionRepository;

/**
 * Web-layer tests for {@link AdminPaymentController} (Requirement 19.2, 12.7): the JSON the Admin
 * Portal's {@code AdminPayment} type reads (portal enum names, refunded total, booking id as the
 * booking reference), the search, and the refund's delegation to {@link PaymentService#refund} with
 * the {@code Idempotency-Key} header. Role gating is covered by {@code PaymentRbacConfigTest}.
 *
 * <p>The list runs over the real {@link AdminPaymentQueryService} and an in-memory repository; the
 * refund's money rules belong to {@code PaymentServiceTest}, so the service is mocked here.
 */
class AdminPaymentControllerTest {

    private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");
    private static final String REFUND_BODY = "{\"amount\":40.00,\"reason\":\"Service not delivered\"}";

    private InMemoryPaymentTransactionRepository repository;
    private PaymentService paymentService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = new InMemoryPaymentTransactionRepository();
        paymentService = mock(PaymentService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new AdminPaymentController(
                        new AdminPaymentQueryService(repository), paymentService, new CallerIdentity()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_FINANCE_ADMIN"))));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private PaymentTransaction save(PaymentMethod method, String gatewayReference, long minutes) {
        PaymentTransaction tx = PaymentTransaction.initiate("key-" + UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"), new BigDecimal("20.00"),
                method, RazorpayGatewayAdapter.GATEWAY_ID, null);
        tx.setGatewayReference(gatewayReference);
        ReflectionTestUtils.setField(tx, "createdAt", T0.plusSeconds(minutes * 60));
        return repository.save(tx);
    }

    // ----- list --------------------------------------------------------------

    @Test
    void listReturnsTheAdminPaymentShapeNewestFirst() throws Exception {
        PaymentTransaction older = save(PaymentMethod.UPI, null, 1);
        PaymentTransaction refunded = save(PaymentMethod.CREDIT_DEBIT_CARD, "pay_abc", 2);
        refunded.transitionTo(TransactionStatus.SUCCESS);
        refunded.applyRefund(new BigDecimal("40.00"));

        mvc.perform(get("/admin/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(refunded.getId().toString()))
                .andExpect(jsonPath("$[0].bookingReference").value(refunded.getBookingId().toString()))
                .andExpect(jsonPath("$[0].customerName").value(Matchers.nullValue()))
                .andExpect(jsonPath("$[0].amount").value(100.00))
                .andExpect(jsonPath("$[0].refundedAmount").value(40.00))
                .andExpect(jsonPath("$[0].currency").value("INR"))
                .andExpect(jsonPath("$[0].method").value("CARD"))
                .andExpect(jsonPath("$[0].status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$[0].gateway").value(RazorpayGatewayAdapter.GATEWAY_ID))
                .andExpect(jsonPath("$[0].createdAt").value("2026-09-01T08:02:00Z"))
                // Never the stored credential, nor fields the portal does not read.
                .andExpect(jsonPath("$[0].paymentCredentialEncrypted").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(older.getId().toString()))
                .andExpect(jsonPath("$[1].status").value("PENDING"))
                .andExpect(jsonPath("$[1].refundedAmount").value(0));
    }

    @Test
    void settledPaymentIsReportedAsCompleted() throws Exception {
        PaymentTransaction tx = save(PaymentMethod.NET_BANKING, null, 1);
        tx.transitionTo(TransactionStatus.SUCCESS);

        mvc.perform(get("/admin/payments"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].method").value("NETBANKING"));
    }

    @Test
    void listFiltersBySearch() throws Exception {
        PaymentTransaction match = save(PaymentMethod.UPI, "pay_ABC123", 1);
        save(PaymentMethod.UPI, "pay_zzz", 2);

        mvc.perform(get("/admin/payments").param("search", "abc1"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(match.getId().toString()));
        mvc.perform(get("/admin/payments").param("search", match.getBookingId().toString()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(match.getId().toString()));
    }

    // ----- refund ------------------------------------------------------------

    @Test
    void refundDelegatesWithTheHeaderKeyAndReturnsTheRow() throws Exception {
        PaymentTransaction tx = save(PaymentMethod.UPI, "pay_abc", 1);
        tx.transitionTo(TransactionStatus.SUCCESS);
        when(paymentService.refund(tx.getId(), new BigDecimal("40.00"), "portal-rf-1")).thenAnswer(inv -> {
            tx.applyRefund(new BigDecimal("40.00"));
            return tx;
        });

        mvc.perform(post("/admin/payments/{id}/refund", tx.getId())
                        .header("Idempotency-Key", "portal-rf-1")
                        .contentType(MediaType.APPLICATION_JSON).content(REFUND_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tx.getId().toString()))
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(40.00));

        verify(paymentService).refund(tx.getId(), new BigDecimal("40.00"), "portal-rf-1");
    }

    @Test
    void refundWithoutAnIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/admin/payments/{id}/refund", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(REFUND_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(post("/admin/payments/{id}/refund", UUID.randomUUID())
                        .header("Idempotency-Key", "  ")
                        .contentType(MediaType.APPLICATION_JSON).content(REFUND_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REQUIRED"));

        verify(paymentService, never()).refund(any(), any(), any());
    }

    @Test
    void refundAmountWithMoreThanTwoDecimalsIsRejected() throws Exception {
        mvc.perform(post("/admin/payments/{id}/refund", UUID.randomUUID())
                        .header("Idempotency-Key", "portal-rf-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":40.001,\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verify(paymentService, never()).refund(any(), any(), any());
    }

    @Test
    void refundRequiresAReason() throws Exception {
        mvc.perform(post("/admin/payments/{id}/refund", UUID.randomUUID())
                        .header("Idempotency-Key", "portal-rf-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":40.00,\"reason\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verify(paymentService, never()).refund(any(), any(), any());
    }

    @Test
    void unknownGatewayOutcomeReachesThePortalAsTheRefundFlowReportsIt() throws Exception {
        when(paymentService.refund(any(), any(), any())).thenThrow(new PaymentException(
                HttpStatus.BAD_GATEWAY, "REFUND_OUTCOME_UNKNOWN", "retry with the same key"));

        mvc.perform(post("/admin/payments/{id}/refund", UUID.randomUUID())
                        .header("Idempotency-Key", "portal-rf-4")
                        .contentType(MediaType.APPLICATION_JSON).content(REFUND_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("REFUND_OUTCOME_UNKNOWN"));
    }
}
