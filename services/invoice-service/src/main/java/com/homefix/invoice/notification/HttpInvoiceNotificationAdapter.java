package com.homefix.invoice.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.homefix.invoice.config.InvoiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Default {@link InvoiceNotificationPort} adapter. Asks the Notification Service to deliver the
 * invoice signed URL to the customer (Requirement 13.3).
 *
 * <p>Delivery is best-effort at the transport level: a transient Notification Service outage must
 * not roll back an already-persisted invoice, so failures are logged and rethrown as a runtime
 * exception only to let the caller decide on retry. Active only when no other
 * {@link InvoiceNotificationPort} bean is present (tests supply a fake).
 */
@Component
public class HttpInvoiceNotificationAdapter implements InvoiceNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(HttpInvoiceNotificationAdapter.class);

    private final RestClient restClient;

    public HttpInvoiceNotificationAdapter(InvoiceProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.getClients().getNotificationServiceBaseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory() {{
                    setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
                    setReadTimeout((int) Duration.ofSeconds(15).toMillis());
                }})
                .build();
    }

    @Override
    public void deliverInvoice(UUID customerId, UUID bookingId, String invoiceNumber, String signedUrl,
                               Instant expiresAt) {
        try {
            restClient.post()
                    .uri("/internal/notifications/invoice-ready")
                    .body(Map.of(
                            "customerId", customerId,
                            "bookingId", bookingId,
                            "invoiceNumber", invoiceNumber,
                            "signedUrl", signedUrl,
                            "expiresAt", expiresAt.toString(),
                            "channels", new String[]{"PUSH", "EMAIL"}))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Failed to deliver invoice {} to customer for booking {}", invoiceNumber, bookingId);
            throw e;
        }
    }
}
