package com.homefix.payment.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What Razorpay Checkout hands the browser after a successful payment, forwarded as-is. The
 * signature is checked server-side and the payment is read back from Razorpay, so none of these
 * values is trusted on its own. Checkout's own snake_case names are accepted too.
 */
public record RazorpayVerifyRequest(
        @NotBlank @Size(max = 64) @JsonAlias("razorpay_order_id") String razorpayOrderId,
        @NotBlank @Size(max = 64) @JsonAlias("razorpay_payment_id") String razorpayPaymentId,
        @NotBlank @Size(max = 128) @JsonAlias("razorpay_signature") String razorpaySignature) {
}
