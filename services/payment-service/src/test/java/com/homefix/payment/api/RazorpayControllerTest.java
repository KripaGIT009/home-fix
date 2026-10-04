package com.homefix.payment.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
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

import com.homefix.payment.domain.PaymentMethod;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.gateway.RazorpayGatewayAdapter;
import com.homefix.payment.service.PaymentService;
import com.homefix.payment.service.RazorpayPaymentService;
import com.homefix.payment.service.RazorpayPaymentService.RazorpayCheckout;

/**
 * Web-layer tests for {@link RazorpayController}: ownership of the customer endpoints, the request
 * body Checkout produces, and the webhook receiving the raw body bytes it is signed over.
 */
@ExtendWith(MockitoExtension.class)
class RazorpayControllerTest {

    @Mock
    private PaymentService paymentService;
    @Mock
    private RazorpayPaymentService razorpayPaymentService;

    private MockMvc mvc;
    private UUID customerId;
    private PaymentTransaction tx;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new RazorpayController(paymentService, razorpayPaymentService, new CallerIdentity()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        customerId = UUID.randomUUID();
        tx = PaymentTransaction.initiate("key", customerId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("500.00"), new BigDecimal("100.00"), PaymentMethod.UPI,
                RazorpayGatewayAdapter.GATEWAY_ID, null);
        tx.setGatewayReference("order_1");
        authenticate(customerId, "CUSTOMER");
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

    @Test
    void checkout_forOwnPendingPayment_returnsWhatCheckoutOpensWith() throws Exception {
        when(paymentService.getTransaction(tx.getId())).thenReturn(tx);
        when(razorpayPaymentService.checkoutFor(tx)).thenReturn(Optional.of(
                new RazorpayCheckout("rzp_test_key", "order_1", 50000, "INR", "HFX-1")));

        mvc.perform(get("/payments/{id}/razorpay/checkout", tx.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyId").value("rzp_test_key"))
                .andExpect(jsonPath("$.orderId").value("order_1"))
                .andExpect(jsonPath("$.amount").value(50000));
    }

    @Test
    void checkout_whenNotAwaitingCheckout_is409() throws Exception {
        when(paymentService.getTransaction(tx.getId())).thenReturn(tx);
        when(razorpayPaymentService.checkoutFor(tx)).thenReturn(Optional.empty());

        mvc.perform(get("/payments/{id}/razorpay/checkout", tx.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_AWAITING_CHECKOUT"));
    }

    @Test
    void checkout_forSomeoneElsesPayment_is403() throws Exception {
        authenticate(UUID.randomUUID(), "CUSTOMER");
        when(paymentService.getTransaction(tx.getId())).thenReturn(tx);

        mvc.perform(get("/payments/{id}/razorpay/checkout", tx.getId()))
                .andExpect(status().isForbidden());
        verify(razorpayPaymentService, never()).checkoutFor(any());
    }

    @Test
    void verify_acceptsCheckoutsOwnFieldNames() throws Exception {
        when(paymentService.getTransaction(tx.getId())).thenReturn(tx);
        when(razorpayPaymentService.confirmCheckout(tx, "order_1", "pay_1", "sig")).thenReturn(tx);

        mvc.perform(post("/payments/{id}/razorpay/verify", tx.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"razorpay_order_id":"order_1","razorpay_payment_id":"pay_1",
                                 "razorpay_signature":"sig"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tx.getId().toString()));
    }

    @Test
    void verify_forSomeoneElsesPayment_is403() throws Exception {
        authenticate(UUID.randomUUID(), "CUSTOMER");
        when(paymentService.getTransaction(tx.getId())).thenReturn(tx);

        mvc.perform(post("/payments/{id}/razorpay/verify", tx.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"razorpayOrderId":"order_1","razorpayPaymentId":"pay_1","razorpaySignature":"sig"}"""))
                .andExpect(status().isForbidden());
        verify(razorpayPaymentService, never()).confirmCheckout(any(), any(), any(), any());
    }

    @Test
    void verify_withMissingFields_is400() throws Exception {
        mvc.perform(post("/payments/{id}/razorpay/verify", tx.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"razorpayOrderId\":\"order_1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void webhook_passesTheExactBodyBytesAndSignature() throws Exception {
        SecurityContextHolder.clearContext();
        String body = "{ \"event\" : \"payment.captured\",  \"payload\": {} }";

        mvc.perform(post("/payments/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", "sig")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
        verify(razorpayPaymentService).handleWebhook(eq(body.getBytes(StandardCharsets.UTF_8)), eq("sig"));
    }
}
