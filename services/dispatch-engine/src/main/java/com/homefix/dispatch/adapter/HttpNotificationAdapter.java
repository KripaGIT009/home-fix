package com.homefix.dispatch.adapter;

import com.homefix.dispatch.config.DispatchClientProperties;
import com.homefix.dispatch.port.NotificationPort;
import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.homefix.shared.resilience.TimeoutProfile;
import com.homefix.shared.resilience.TransientFailures;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Default {@link NotificationPort} adapter. On a failed search it asks the Notification Service to
 * send the customer a push + SMS and posts an internal dispatcher-team alert (Requirement 8.9).
 *
 * <p>Notification is not on the critical dispatch path, so it uses the standard 15 s timeout
 * profile (Requirement 24.3) with retry and a circuit breaker (Requirements 24.1, 24.2). Delivery
 * is best-effort: a transport failure here must not throw the dispatch flow into retry (the booking
 * is already SEARCHING_FAILED), so the fallback is a no-op that returns after emitting the shared
 * WARN log naming the {@code notification-service} dependency (Requirement 24.4).
 *
 * <p>Active only when no other {@link NotificationPort} bean is present (tests supply a fake).
 */
@Component
public class HttpNotificationAdapter implements NotificationPort {

    /** Downstream dependency name used for breaker keying, metrics, and the WARN log. */
    static final String DEPENDENCY = "notification-service";

    private final RestClient restClient;
    private final ResilientCall<Void> resilientCall;

    public HttpNotificationAdapter(DispatchClientProperties properties, ResilienceFactory resilienceFactory) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.getNotificationServiceBaseUrl())
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
                    setReadTimeout((int) Duration.ofSeconds(15).toMillis());
                }})
                .build();
        // Best-effort: degrade to a no-op so a notification outage never fails the dispatch flow.
        this.resilientCall = ResilientCall.forDependency(
                resilienceFactory, DEPENDENCY, TimeoutProfile.STANDARD, cause -> null);
    }

    @Override
    public void notifyCustomerNoProviderAvailable(UUID bookingId, UUID customerId) {
        resilientCall.execute(() -> {
            restClient.post()
                    .uri("/internal/notifications/no-provider-available")
                    .body(Map.of("bookingId", bookingId, "customerId", customerId,
                            "channels", new String[]{"PUSH", "SMS"}))
                    .retrieve()
                    .onStatus(status -> status.is5xxServerError(), (req, res) -> {
                        throw new TransientFailures.ServerErrorException(
                                res.getStatusCode().value(), "Notification Service returned 5xx");
                    })
                    .toBodilessEntity();
            return null;
        });
    }

    @Override
    public void alertDispatcherTeam(UUID bookingId) {
        resilientCall.execute(() -> {
            restClient.post()
                    .uri("/internal/notifications/dispatcher-alert")
                    .body(Map.of("bookingId", bookingId, "reason", "SEARCHING_FAILED"))
                    .retrieve()
                    .onStatus(status -> status.is5xxServerError(), (req, res) -> {
                        throw new TransientFailures.ServerErrorException(
                                res.getStatusCode().value(), "Notification Service returned 5xx");
                    })
                    .toBodilessEntity();
            return null;
        });
    }
}
