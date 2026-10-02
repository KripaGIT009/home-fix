package com.homefix.payment.wallet;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Production {@link ProviderWalletClientPort}: credits a paid booking's earning through the
 * Provider Service's {@code POST /internal/providers/{providerId}/earnings}, with the shared
 * {@code X-Internal-Api-Key} (Requirement 12.10). Selected with
 * {@code homefix.payment.provider-wallet.client=http}.
 *
 * <p>The Provider Service applies a booking's credit once however often it is sent, which is what
 * the port's at-least-once contract needs. No resilience wrapper here: {@code PaymentService}
 * already retries a failed credit in line and the {@code WalletCreditSweeper} re-sends any credit
 * still owed, so every failure is simply reported as a {@link WalletCreditException}.
 *
 * <p>Settlement reversals are not wired: the Provider Service has no credit-back endpoint yet. They
 * are logged, as before, and Finance_Admin is alerted for every failed settlement regardless, so
 * the money is reconciled by hand rather than silently lost.
 */
@Component
@ConditionalOnProperty(name = "homefix.payment.provider-wallet.client", havingValue = "http")
public class HttpProviderWalletClientAdapter implements ProviderWalletClientPort {

    private static final Logger log = LoggerFactory.getLogger(HttpProviderWalletClientAdapter.class);

    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;

    public HttpProviderWalletClientAdapter(
            @Value("${homefix.payment.provider-service-url:http://provider-service:8083}") String baseUrl,
            @Value("${homefix.payment.internal-api-key:}") String internalApiKey) {
        if (internalApiKey == null || internalApiKey.isBlank()) {
            log.error("homefix.payment.internal-api-key (INTERNAL_API_KEY) is not configured; the Provider "
                    + "Service will refuse every wallet credit, so providers will not be paid");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) CALL_TIMEOUT.toMillis());
        requestFactory.setReadTimeout((int) CALL_TIMEOUT.toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(INTERNAL_KEY_HEADER, internalApiKey == null ? "" : internalApiKey)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public void creditEarning(UUID providerId, UUID bookingId, BigDecimal gross, BigDecimal platformFee,
                              BigDecimal netAmount) {
        try {
            restClient.post()
                    .uri("/internal/providers/{providerId}/earnings", providerId)
                    .body(new CreditRequest(bookingId, null, gross, platformFee))
                    .retrieve()
                    .toBodilessEntity();
            log.info("WALLET_CREDIT provider={} booking={} gross={} platformFee={} net={}",
                    providerId, bookingId, gross, platformFee, netAmount);
        } catch (RestClientException e) {
            throw new WalletCreditException("Provider Service refused or failed the wallet credit for booking "
                    + bookingId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void creditSettlementReversal(UUID providerId, UUID settlementId, BigDecimal amount) {
        log.warn("WALLET_CREDIT_BACK provider={} settlement={} amount={} (failed settlement reversal; "
                + "no Provider Service endpoint yet, reconcile by hand)", providerId, settlementId, amount);
    }

    /** Body of the Provider Service's credit endpoint; the net is derived there. */
    record CreditRequest(UUID bookingId, String bookingReference, BigDecimal gross, BigDecimal platformFee) {
    }
}
