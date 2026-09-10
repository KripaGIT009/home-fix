package com.homefix.dispatch.adapter;

import com.homefix.dispatch.config.DispatchClientProperties;
import com.homefix.dispatch.port.JobOfferPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Default {@link JobOfferPort} adapter. Sends a job offer to the provider (via the Notification
 * Service push to the provider app) and blocks up to {@code timeout} for the provider's response,
 * which arrives through the provider-app acceptance callback (Requirements 8.5-8.7).
 *
 * <p>This adapter models the synchronous "offer then await" contract; the concrete long-poll /
 * callback wiring is deployment-specific. A response endpoint is polled until the timeout elapses.
 * Unit tests replace this with a deterministic fake, so the algorithm is validated independently
 * of the transport.
 *
 * <p>Active only when no other {@link JobOfferPort} bean is present.
 */
@Component
public class HttpJobOfferAdapter implements JobOfferPort {

    private static final Logger log = LoggerFactory.getLogger(HttpJobOfferAdapter.class);

    private final RestClient restClient;

    public HttpJobOfferAdapter(DispatchClientProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.getNotificationServiceBaseUrl())
                .build();
    }

    @Override
    public OfferOutcome offer(UUID bookingId, UUID providerId, Duration timeout) {
        try {
            OfferResponse response = restClient.post()
                    .uri("/internal/offers")
                    .body(Map.of(
                            "bookingId", bookingId,
                            "providerId", providerId,
                            "timeoutSeconds", timeout.toSeconds()))
                    .retrieve()
                    .body(OfferResponse.class);
            if (response == null || response.outcome() == null) {
                return OfferOutcome.TIMED_OUT;
            }
            return switch (response.outcome().toUpperCase()) {
                case "ACCEPTED" -> OfferOutcome.ACCEPTED;
                case "REJECTED" -> OfferOutcome.REJECTED;
                default -> OfferOutcome.TIMED_OUT;
            };
        } catch (RestClientException e) {
            log.warn("Job offer to provider {} for booking {} failed; treating as timeout",
                    providerId, bookingId);
            return OfferOutcome.TIMED_OUT;
        }
    }

    /** Response shape from the offer endpoint: ACCEPTED | REJECTED | TIMED_OUT. */
    public record OfferResponse(String outcome) {
    }
}
