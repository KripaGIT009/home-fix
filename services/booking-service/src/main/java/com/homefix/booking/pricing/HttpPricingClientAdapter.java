package com.homefix.booking.pricing;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.homefix.booking.client.BearerTokenRelay;
import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.homefix.shared.resilience.TimeoutProfile;
import com.homefix.shared.resilience.TransientFailures;

/**
 * Production {@link PricingClientPort}: calls the Pricing Engine over HTTP for an itemised
 * estimate (Requirement 7.3, 6.9). Selected with {@code homefix.pricing.client=http}.
 *
 * <p>Runs under the shared resilience stack (Requirement 24): a bounded per-call timeout,
 * retry with exponential backoff for transient failures, and a circuit breaker. Pricing is not
 * degradable — a booking must never be created without a real estimate (Requirement 7.4) — so
 * the fallback raises {@link PricingUnavailableException} rather than inventing a total; the
 * booking flow turns that into a 503 and creates nothing.
 */
@Component
@ConditionalOnProperty(name = "homefix.pricing.client", havingValue = "http")
public class HttpPricingClientAdapter implements PricingClientPort {

    /** Downstream dependency name used for breaker keying, metrics, and the WARN log. */
    static final String DEPENDENCY = "pricing-engine";

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final BearerTokenRelay tokenRelay;
    private final ZoneId pricingZone;
    private final ResilientCall<PriceBreakdownResponse> resilientCall;

    public HttpPricingClientAdapter(@Value("${homefix.pricing.base-url}") String baseUrl,
                                    // Must match the Pricing Engine's own homefix.pricing.zone-id:
                                    // it decides which wall-clock times fall inside the night and
                                    // weekend surcharge windows (Requirement 6.5, 6.6).
                                    @Value("${homefix.pricing.zone:UTC}") String pricingZone,
                                    BearerTokenRelay tokenRelay,
                                    ResilienceFactory resilienceFactory) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) CALL_TIMEOUT.toMillis());
        requestFactory.setReadTimeout((int) CALL_TIMEOUT.toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.tokenRelay = tokenRelay;
        this.pricingZone = ZoneId.of(pricingZone);
        this.resilientCall = ResilientCall.forDependency(
                resilienceFactory, DEPENDENCY, TimeoutProfile.CRITICAL_PATH,
                cause -> {
                    throw new PricingUnavailableException(
                            "Pricing Engine unavailable: " + cause.getMessage(), cause);
                });
    }

    @Override
    public PriceEstimate estimate(PriceEstimateRequest request) {
        return toEstimate(quote(new PriceRequestPayload(
                request.subcategoryId(),
                request.emergency(),
                null,
                localTime(request.scheduledAt()),
                request.customerId())));
    }

    @Override
    public PriceEstimate recalculateWithParts(PartsRecalculationRequest request) {
        // A re-quote carrying the new parts total: the Pricing Engine re-derives every other
        // component from the booking's original inputs, so the emergency multiplier and the
        // night/weekend surcharges the first estimate used are preserved (Requirement 6.8, 11.3).
        return toEstimate(quote(new PriceRequestPayload(
                request.subcategoryId(),
                request.emergency(),
                request.partsTotal(),
                localTime(request.scheduledAt()),
                null)));
    }

    private PriceBreakdownResponse quote(PriceRequestPayload payload) {
        // Read the caller's token here, on the request thread: ResilientCall bounds each attempt
        // by running it on its own executor, and the request context is a plain (non-inheritable)
        // ThreadLocal, so resolving it inside the lambda would find nothing and the Pricing Engine
        // would reject the call as anonymous.
        String authorization = tokenRelay.currentAuthorizationHeader().orElse(null);
        return resilientCall.execute(() -> restClient.post()
                .uri("/pricing/estimate")
                .headers(headers -> {
                    if (authorization != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, authorization);
                    }
                })
                .body(payload)
                .retrieve()
                // 5xx is transient: surface it so retry and the breaker act on it (Req 24.2).
                .onStatus(status -> status.is5xxServerError(), (req, res) -> {
                    throw new TransientFailures.ServerErrorException(
                            res.getStatusCode().value(), "Pricing Engine returned 5xx");
                })
                // 4xx is a permanent answer (unknown subcategory, rejected token): retrying
                // cannot help, so fail the booking straight away rather than burning attempts.
                .onStatus(status -> status.is4xxClientError(), (req, res) -> {
                    throw new PricingUnavailableException(
                            "Pricing Engine rejected the estimate request with "
                                    + res.getStatusCode());
                })
                .body(PriceBreakdownResponse.class));
    }

    private LocalDateTime localTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, pricingZone);
    }

    private PriceEstimate toEstimate(PriceBreakdownResponse breakdown) {
        if (breakdown == null || breakdown.total() == null) {
            throw new PricingUnavailableException("Pricing Engine returned an empty estimate");
        }
        Map<String, BigDecimal> components = new LinkedHashMap<>();
        components.put("basePrice", breakdown.basePrice());
        components.put("distanceCharge", breakdown.distanceCharge());
        components.put("timeCharge", breakdown.timeCharge());
        components.put("partsMaterialsCharge", breakdown.partsMaterialsCharge());
        components.put("emergencyCharge", breakdown.emergencyCharge());
        components.put("weekendSurcharge", breakdown.weekendSurcharge());
        components.put("nightSurcharge", breakdown.nightSurcharge());
        components.put("demandSurgeCharge", breakdown.demandSurgeCharge());
        components.put("platformFee", breakdown.platformFee());
        components.put("taxes", breakdown.taxes());
        components.put("discountAmount", breakdown.discountAmount());
        components.put("couponAmount", breakdown.couponAmount());
        components.values().removeIf(Objects::isNull);
        return new PriceEstimate(breakdown.total(), components);
    }

    /** Request payload accepted by {@code POST /pricing/estimate}. */
    record PriceRequestPayload(
            UUID subcategoryId,
            boolean emergency,
            BigDecimal partsMaterialsCharge,
            LocalDateTime scheduledLocalTime,
            UUID userId) {
    }

    /** Itemised breakdown returned by {@code POST /pricing/estimate}. */
    record PriceBreakdownResponse(
            BigDecimal basePrice,
            BigDecimal distanceCharge,
            BigDecimal timeCharge,
            BigDecimal partsMaterialsCharge,
            BigDecimal emergencyCharge,
            BigDecimal weekendSurcharge,
            BigDecimal nightSurcharge,
            BigDecimal demandSurgeCharge,
            BigDecimal platformFee,
            BigDecimal taxes,
            BigDecimal discountAmount,
            BigDecimal couponAmount,
            BigDecimal total) {
    }
}
