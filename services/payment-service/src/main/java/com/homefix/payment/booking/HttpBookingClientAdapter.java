package com.homefix.payment.booking;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.homefix.payment.service.PaymentException;
import com.homefix.shared.resilience.FallbackDecision;
import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.homefix.shared.resilience.TimeoutProfile;
import com.homefix.shared.resilience.TransientFailures;

/**
 * Production {@link BookingClientPort}: the Booking Service's internal
 * {@code GET /internal/bookings/{id}/payment-facts} and
 * {@code POST /internal/bookings/{id}/payment-pending}, sent with the shared
 * {@code X-Internal-Api-Key} (the same {@code INTERNAL_API_KEY} the Booking Service checks).
 *
 * <p>Checkout is a customer request path, so both calls run under the shared resilience stack with
 * the {@link TimeoutProfile#CRITICAL_PATH} budget (Requirement 24.3), which also bounds the socket
 * connect and read, with retry for transient failures (24.2) and one circuit breaker keyed on
 * {@value #DEPENDENCY} (24.1).
 *
 * <p><strong>Retrying the POST is safe</strong> because the Booking Service makes it idempotent: a
 * booking already in {@code PAYMENT_PENDING} is answered 200 unchanged, so a retry after an attempt
 * that landed but timed out gets the same answer instead of a conflict.
 *
 * <p>Answers are not outages: a 404 (unknown booking, or a customer who is not the booking's) and a
 * 409 (booking not payable) are returned as results, so they are never retried, never trip the
 * breaker and are not logged as a degraded dependency. Everything else the Booking Service cannot
 * answer (unreachable, timed out, 5xx, breaker open, the credential refused, an unreadable body)
 * fails <em>closed</em> as {@code 503 BOOKING_SERVICE_UNAVAILABLE}: the payment is not started, so
 * nothing is charged on a booking whose state could not be confirmed.
 *
 * <p>A missing credential is logged as an ERROR at startup rather than failing it (the rest of the
 * Payment Service, i.e. callbacks, refunds and settlements, does not need it), and every booking
 * payment then answers 503 without a network call.
 */
@Component
public class HttpBookingClientAdapter implements BookingClientPort {

    private static final Logger log = LoggerFactory.getLogger(HttpBookingClientAdapter.class);

    /** Downstream dependency name used for breaker keying, metrics, and the WARN log. */
    static final String DEPENDENCY = "booking-service";

    /** Header carrying the shared service credential the Booking Service expects on /internal/**. */
    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    private final RestClient restClient;
    private final ResilientCall<Optional<BookingPaymentFacts>> factsCall;
    private final ResilientCall<PendingOutcome> pendingCall;
    private final boolean configured;

    @Autowired
    public HttpBookingClientAdapter(
            ResilienceFactory resilienceFactory,
            @Value("${homefix.payment.booking-service-url:http://booking-service:8084}") String baseUrl,
            @Value("${homefix.payment.internal-api-key:}") String internalApiKey) {
        this(resilienceFactory, baseUrl, internalApiKey, TimeoutProfile.CRITICAL_PATH.timeout());
    }

    /** Explicit-timeout constructor so tests can exercise the timeout path without waiting 5 s. */
    HttpBookingClientAdapter(ResilienceFactory resilienceFactory, String baseUrl, String internalApiKey,
                             Duration timeout) {
        this.configured = internalApiKey != null && !internalApiKey.isBlank();
        if (!configured) {
            log.error("homefix.payment.internal-api-key (INTERNAL_API_KEY) is not configured; the Booking "
                    + "Service would refuse every payment-facts lookup, so no booking can be paid");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) timeout.toMillis());
        requestFactory.setReadTimeout((int) timeout.toMillis());
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory);
        if (configured) {
            builder.defaultHeader(INTERNAL_KEY_HEADER, internalApiKey);
        }
        this.restClient = builder.build();
        this.factsCall = ResilientCall.forDependency(resilienceFactory, DEPENDENCY, timeout, failClosed());
        this.pendingCall = ResilientCall.forDependency(resilienceFactory, DEPENDENCY, timeout, failClosed());
    }

    @Override
    public Optional<BookingPaymentFacts> paymentFacts(UUID bookingId) {
        if (!configured) {
            throw unavailable();
        }
        return factsCall.execute(() -> restClient.get()
                .uri("/internal/bookings/{bookingId}/payment-facts", bookingId)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.value() == HttpStatus.NOT_FOUND.value()) {
                        return Optional.<BookingPaymentFacts>empty();
                    }
                    requireSuccess(status);
                    return Optional.of(requireBody(response.bodyTo(BookingPaymentFacts.class)));
                }));
    }

    @Override
    public BookingPaymentFacts markPaymentPending(UUID bookingId, UUID customerId) {
        if (!configured) {
            throw unavailable();
        }
        PendingOutcome outcome = pendingCall.execute(() -> restClient.post()
                .uri("/internal/bookings/{bookingId}/payment-pending", bookingId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PaymentPendingRequest(customerId))
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.value() == HttpStatus.NOT_FOUND.value()
                            || status.value() == HttpStatus.CONFLICT.value()) {
                        return new PendingOutcome(null, status.value());
                    }
                    requireSuccess(status);
                    return new PendingOutcome(requireBody(response.bodyTo(BookingPaymentFacts.class)), 0);
                }));
        if (outcome.refusedWith() == HttpStatus.NOT_FOUND.value()) {
            throw PaymentException.bookingNotFound(bookingId);
        }
        if (outcome.refusedWith() == HttpStatus.CONFLICT.value()) {
            throw PaymentException.bookingNotPayable(bookingId);
        }
        return outcome.facts();
    }

    /** 5xx is transient (retried, counted by the breaker); any other non-2xx is a refusal. */
    private static void requireSuccess(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            throw new TransientFailures.ServerErrorException(status.value(),
                    "Booking Service returned " + status.value());
        }
        if (!status.is2xxSuccessful()) {
            // 401/403 = credential refused; anything else is a contract mismatch. Not transient.
            throw new IllegalStateException("Booking Service refused the request with HTTP " + status.value());
        }
    }

    private static BookingPaymentFacts requireBody(BookingPaymentFacts facts) {
        if (facts == null || facts.bookingId() == null) {
            throw new IllegalStateException("Booking Service returned no payment facts");
        }
        return facts;
    }

    private static <T> FallbackDecision<T> failClosed() {
        return cause -> {
            log.warn("Booking Service unavailable ({}); refusing to start the payment: {}",
                    DEPENDENCY, String.valueOf(cause));
            throw unavailable();
        };
    }

    private static PaymentException unavailable() {
        return new PaymentException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_SERVICE_UNAVAILABLE",
                "The Booking Service is unavailable; the payment was not started and nothing was charged");
    }

    /** Request body of {@code POST /internal/bookings/{id}/payment-pending}. */
    record PaymentPendingRequest(UUID customerId) {
    }

    /** Result of the payment-pending call: the facts, or the 404/409 the Booking Service answered. */
    private record PendingOutcome(BookingPaymentFacts facts, int refusedWith) {
    }
}
