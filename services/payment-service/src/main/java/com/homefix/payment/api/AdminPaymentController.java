package com.homefix.payment.api;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.payment.api.dto.AdminPaymentResponse;
import com.homefix.payment.api.dto.AdminRefundRequest;
import com.homefix.payment.service.AdminPaymentQueryService;
import com.homefix.payment.service.PaymentException;
import com.homefix.payment.service.PaymentService;

import jakarta.validation.Valid;

/**
 * The Admin Portal's Payment and Refund Management endpoints (Requirement 19.2, 12.7), reached
 * through the gateway's {@code /admin/payments} route unchanged.
 *
 * <p>Only the finance tier gets here: {@code PaymentRbacConfig} restricts
 * {@code /admin/payments/**} to ADMIN, SUPER_ADMIN and FINANCE_ADMIN, the same roles as
 * {@code POST /payments/{id}/refunds}.
 *
 * <h2>The refund is the existing refund</h2>
 * {@link #refund} adds no rules of its own. It hands the amount and the client's idempotency key to
 * {@link PaymentService#refund}, so a refund issued from the portal gets everything a refund issued
 * through {@code /payments/{id}/refunds} does: the two-decimal and over-refund checks, the
 * PENDING-before-the-gateway reservation that blocks a concurrent second refund, the
 * same-key-never-refunds-twice guarantee, and the {@code 502 REFUND_OUTCOME_UNKNOWN} answer (the
 * refund stays PENDING; retry with the <em>same</em> key) when the gateway's outcome is lost.
 *
 * <p>The key travels in the {@code Idempotency-Key} header rather than the body because the
 * portal's request type is {@code {amount, reason}}. It is required: a refund without one could
 * not be retried safely after a timeout, which is exactly when a staff member presses the button
 * again.
 *
 * <p>{@code @PathVariable}/{@code @RequestParam}/{@code @RequestHeader} names are spelled out
 * explicitly because the build does not enable the {@code -parameters} compiler flag.
 */
@RestController
@RequestMapping("/admin/payments")
public class AdminPaymentController {

    private static final Logger log = LoggerFactory.getLogger(AdminPaymentController.class);

    /** Header carrying the client-chosen refund idempotency key. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final AdminPaymentQueryService queryService;
    private final PaymentService paymentService;
    private final CallerIdentity callerIdentity;

    public AdminPaymentController(AdminPaymentQueryService queryService, PaymentService paymentService,
                                  CallerIdentity callerIdentity) {
        this.queryService = queryService;
        this.paymentService = paymentService;
        this.callerIdentity = callerIdentity;
    }

    /**
     * {@code GET /admin/payments?search} — transactions newest first, at most
     * {@value AdminPaymentQueryService#ADMIN_LIST_LIMIT}; {@code search} matches a substring of the
     * transaction id, booking id or gateway reference, ignoring case.
     */
    @GetMapping
    public List<AdminPaymentResponse> list(@RequestParam(value = "search", required = false) String search) {
        return queryService.search(search).stream().map(AdminPaymentResponse::from).toList();
    }

    /**
     * {@code POST /admin/payments/{id}/refund} — refund a transaction fully or partially. Requires
     * the {@code Idempotency-Key} header (400 {@code IDEMPOTENCY_KEY_REQUIRED} without it); answers
     * the refunded transaction as a list row.
     *
     * <p>The reason has no column on the refund record, so it is logged at INFO with the acting
     * staff member and the key — before the gateway is called, so the line exists even when the
     * outcome is unknown, and with the key so it can be matched to the refund row.
     */
    @PostMapping("/{id}/refund")
    public AdminPaymentResponse refund(@PathVariable("id") UUID id,
                                       @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
                                       String idempotencyKey,
                                       @Valid @RequestBody AdminRefundRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "The " + IDEMPOTENCY_KEY_HEADER + " header is required to issue a refund");
        }
        log.info("Admin refund requested: transaction={} amount={} idempotencyKey={} actorId={} reason={}",
                id, request.amount(), idempotencyKey, callerIdentity.requireCallerId(), request.reason());
        return AdminPaymentResponse.from(paymentService.refund(id, request.amount(), idempotencyKey));
    }
}
