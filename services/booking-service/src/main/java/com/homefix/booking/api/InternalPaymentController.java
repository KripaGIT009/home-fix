package com.homefix.booking.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.booking.api.dto.BookingPaymentFactsResponse;
import com.homefix.booking.api.dto.PaymentPendingRequest;
import com.homefix.booking.service.BookingPaymentService;

import jakarta.validation.Valid;

/**
 * Service-to-service endpoints the Payment Service calls before it charges a booking
 * (Requirement 12.1).
 *
 * <p>Lives on the {@code /internal/**} surface for the same reasons as
 * {@link InternalDispatchController}: it is not routed through the API Gateway, carries no end-user
 * token, and is authenticated by the shared internal credential enforced by
 * {@code InternalApiKeyFilter}. The Payment Service authenticates the customer and passes their id;
 * the ownership check against the booking is made here, by {@link BookingPaymentService}.
 *
 * <p>The settlement itself arrives as the {@code PaymentCompleted} event, not as a call here; see
 * {@code PaymentCompletedConsumer}.
 */
@RestController
@RequestMapping("/internal/bookings")
public class InternalPaymentController {

    private final BookingPaymentService bookingPaymentService;

    public InternalPaymentController(BookingPaymentService bookingPaymentService) {
        this.bookingPaymentService = bookingPaymentService;
    }

    /**
     * {@code GET /internal/bookings/{bookingId}/payment-facts} — the booking's customer, provider,
     * status and amount due. 404 BOOKING_NOT_FOUND for an unknown id.
     */
    @GetMapping("/{bookingId}/payment-facts")
    public ResponseEntity<BookingPaymentFactsResponse> paymentFacts(@PathVariable UUID bookingId) {
        return ResponseEntity.ok(BookingPaymentFactsResponse.of(
                bookingPaymentService.paymentFacts(bookingId)));
    }

    /**
     * {@code POST /internal/bookings/{bookingId}/payment-pending} — the customer has asked to pay;
     * the booking moves to PAYMENT_PENDING and its payment facts are returned.
     *
     * <p>Safe to retry: a booking already PAYMENT_PENDING answers 200 unchanged. 404 when the booking
     * is missing or not this customer's, 409 BOOKING_NOT_PAYABLE when it is in no payable state.
     */
    @PostMapping("/{bookingId}/payment-pending")
    public ResponseEntity<BookingPaymentFactsResponse> paymentPending(
            @PathVariable UUID bookingId,
            @Valid @RequestBody PaymentPendingRequest request) {
        return ResponseEntity.ok(BookingPaymentFactsResponse.of(
                bookingPaymentService.markPaymentPending(bookingId, request.customerId())));
    }
}
