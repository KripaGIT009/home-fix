package com.homefix.pricing.coupon;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.homefix.pricing.config.PromotionClientProperties;
import com.homefix.pricing.service.PricingException;

/**
 * {@link CouponDiscountPort} backed by the Promotion Service's service-to-service quote endpoint,
 * {@code GET /internal/coupons/{code}/quote?orderValue=..[&userId=..]} (Requirement 6.10, 21.2).
 *
 * <p>The Pricing Engine used to keep its own in-memory coupon table, which nothing could ever
 * populate, so every coupon was "not found" (CODEBASE_REVIEW 8.3). Delegating to the coupon owner
 * also means the discount arithmetic (percentage with a cap, flat amount clamped to the order) and
 * the constraint error codes are the Promotion Service's, not a second copy that could drift.
 *
 * <p>The endpoint is outside the public surface, so every call presents the shared credential in
 * {@code X-Internal-Api-Key} ({@code INTERNAL_API_KEY}), as the Dispatch Engine does towards the
 * Booking and Customer Services. Outcomes:
 * <ul>
 *   <li>200 — the discount, clamped to the order value.</li>
 *   <li>404 carrying {@code COUPON_NOT_FOUND} — 422 {@code COUPON_NOT_FOUND}, as before.</li>
 *   <li>422 — the coupon exists but does not apply; the Promotion Service's constraint code
 *       ({@code COUPON_EXPIRED}, {@code MIN_ORDER_VALUE_NOT_MET}, ...) and message are passed through
 *       as a 422.</li>
 *   <li>Anything else — connection failure, timeout, 5xx, a refused credential, an unreadable body,
 *       or a 404 without that error code (a wrong base URL or a Promotion Service build without the
 *       endpoint) — 503 {@code COUPON_SERVICE_UNAVAILABLE}. The estimate is refused rather than
 *       silently priced without the coupon the customer asked for, and a bare 404 is not read as
 *       "no such coupon" so a misrouted deployment does not reject every code as unknown.</li>
 * </ul>
 *
 * <p>No retry and no circuit breaker here: the call is bounded by short connect/read timeouts (see
 * {@link PromotionClientProperties}) and the Booking Service, the estimate's main caller, already
 * retries a 5xx under its own breaker. Retrying at both layers would multiply the attempts and could
 * overrun the caller's 5-second budget.
 */
@Component
public class PromotionCouponDiscountAdapter implements CouponDiscountPort {

    private static final Logger log = LoggerFactory.getLogger(PromotionCouponDiscountAdapter.class);

    /** Header carrying the shared service credential the Promotion Service expects on /internal/**. */
    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    static final String QUOTE_PATH = "/internal/coupons/{code}/quote";

    /** Error code the Promotion Service returns when the code matches no coupon. */
    static final String COUPON_NOT_FOUND = "COUPON_NOT_FOUND";

    /** Used for a 422 that arrives without an error code of its own. */
    static final String COUPON_NOT_APPLICABLE = "COUPON_NOT_APPLICABLE";

    private static final ParameterizedTypeReference<Map<String, Object>> ERROR_BODY =
            new ParameterizedTypeReference<>() { };

    /** Null when no credential is configured; every coupon quote then fails with a 503. */
    private final RestClient restClient;

    public PromotionCouponDiscountAdapter(PromotionClientProperties properties) {
        String key = properties.getInternalApiKey();
        if (key == null || key.isBlank()) {
            // Not fatal at startup: estimates without a coupon do not need the Promotion Service,
            // and pricing sits on the booking critical path.
            log.error("homefix.pricing.promotion.internal-api-key (INTERNAL_API_KEY) is not configured; "
                    + "every estimate that carries a coupon will fail with COUPON_SERVICE_UNAVAILABLE");
            this.restClient = null;
            return;
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(INTERNAL_KEY_HEADER, key)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public BigDecimal discountFor(String code, UUID userId, BigDecimal orderValue) {
        if (restClient == null) {
            throw unavailable(code);
        }
        try {
            return restClient.get()
                    .uri(builder -> {
                        builder.path(QUOTE_PATH).queryParam("orderValue", orderValue.toPlainString());
                        if (userId != null) {
                            builder.queryParam("userId", userId);
                        }
                        return builder.build(code);
                    })
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (response.getStatusCode().is2xxSuccessful()) {
                            QuoteBody body = response.bodyTo(QuoteBody.class);
                            if (body == null || body.discountAmount() == null
                                    || body.discountAmount().signum() < 0) {
                                throw new IllegalStateException(
                                        "Promotion Service returned no usable discount");
                            }
                            return body.discountAmount().min(orderValue);
                        }
                        if (status == 404 || status == 422) {
                            Map<String, Object> error = readError(response);
                            String errorCode = text(error, "errorCode");
                            if (status == 404 && COUPON_NOT_FOUND.equals(errorCode)) {
                                throw PricingException.coupon(COUPON_NOT_FOUND,
                                        "Coupon '" + code + "' does not exist");
                            }
                            if (status == 422) {
                                String message = text(error, "message");
                                throw PricingException.coupon(
                                        errorCode == null ? COUPON_NOT_APPLICABLE : errorCode,
                                        message == null
                                                ? "Coupon '" + code + "' cannot be applied to this order"
                                                : message);
                            }
                        }
                        if (status == 401 || status == 403) {
                            log.error("Promotion Service refused the coupon quote with HTTP {}; check that "
                                    + "INTERNAL_API_KEY matches across services", status);
                        }
                        throw new IllegalStateException("Promotion Service coupon quote returned HTTP " + status);
                    });
        } catch (PricingException e) {
            throw e;
        } catch (RuntimeException e) {
            // Connection refused, a timeout, an unexpected status or an unreadable body: the coupon
            // could not be priced. Never log the credential; the exception text does not carry it.
            log.warn("Coupon quote from the Promotion Service failed: {}", e.toString());
            throw unavailable(code);
        }
    }

    private static PricingException unavailable(String code) {
        return PricingException.couponServiceUnavailable(
                "Coupon '" + code + "' cannot be checked right now; retry, or request the estimate "
                        + "without the coupon");
    }

    /** The shared error envelope, or an empty map when the body is missing or not JSON. */
    private static Map<String, Object> readError(ConvertibleClientHttpResponse response) {
        try {
            Map<String, Object> body = response.bodyTo(ERROR_BODY);
            return body == null ? Map.of() : body;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static String text(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /** The subset of the Promotion Service's quote response this service reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuoteBody(UUID couponId, String code, BigDecimal discountAmount) {
    }
}
