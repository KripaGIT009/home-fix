package com.homefix.payment.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.homefix.payment.service.InitiatePaymentCommand;
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

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new PaymentController(paymentService, new CallerIdentity()))
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
    void initiate_returns201WithTransactionBody() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        PaymentTransaction tx = pending(customerId, bookingId, providerId);
        when(paymentService.initiatePayment(any(InitiatePaymentCommand.class))).thenReturn(tx);

        LinkedHashMap<String, Object> req = new LinkedHashMap<>();
        req.put("customerId", customerId);
        req.put("bookingId", bookingId);
        req.put("providerId", providerId);
        req.put("amount", "100.00");
        req.put("platformFee", "20.00");
        req.put("method", "UPI");
        req.put("gatewayId", RazorpayGatewayAdapter.GATEWAY_ID);

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(tx.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.providerNetEarning").value(80.00));
    }

    @Test
    void initiate_missingRequiredField_returns400ValidationEnvelope() throws Exception {
        // amount omitted -> @NotNull violation surfaced by GlobalExceptionHandler.
        String body = """
                {"customerId":"%s","bookingId":"%s","providerId":"%s","method":"UPI","gatewayId":"razorpay"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void initiate_unknownGateway_surfacesDomainErrorEnvelope() throws Exception {
        when(paymentService.initiatePayment(any(InitiatePaymentCommand.class)))
                .thenThrow(PaymentException.validation("Unknown gateway: paypal"));

        String body = """
                {"customerId":"%s","bookingId":"%s","providerId":"%s","amount":"10.00",
                 "method":"UPI","gatewayId":"paypal"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
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

    @Test
    void initiate_forAnotherCustomer_isForbidden() throws Exception {
        UUID callerA = UUID.randomUUID();
        UUID callerB = UUID.randomUUID();
        authenticate(callerA, "CUSTOMER");

        LinkedHashMap<String, Object> req = new LinkedHashMap<>();
        req.put("customerId", callerB);
        req.put("bookingId", UUID.randomUUID());
        req.put("providerId", UUID.randomUUID());
        req.put("amount", "100.00");
        req.put("platformFee", "20.00");
        req.put("method", "UPI");
        req.put("gatewayId", RazorpayGatewayAdapter.GATEWAY_ID);

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void initiate_forSelf_returns201() throws Exception {
        UUID callerA = UUID.randomUUID();
        PaymentTransaction tx = pending(callerA, UUID.randomUUID(), UUID.randomUUID());
        when(paymentService.initiatePayment(any(InitiatePaymentCommand.class))).thenReturn(tx);
        authenticate(callerA, "CUSTOMER");

        LinkedHashMap<String, Object> req = new LinkedHashMap<>();
        req.put("customerId", callerA);
        req.put("bookingId", UUID.randomUUID());
        req.put("providerId", UUID.randomUUID());
        req.put("amount", "100.00");
        req.put("platformFee", "20.00");
        req.put("method", "UPI");
        req.put("gatewayId", RazorpayGatewayAdapter.GATEWAY_ID);

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(tx.getId().toString()));
    }
}
