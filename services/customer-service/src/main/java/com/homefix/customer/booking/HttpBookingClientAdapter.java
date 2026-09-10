package com.homefix.customer.booking;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Default {@link BookingClientPort} adapter that queries the Booking Service over HTTP.
 *
 * <p>Calls {@code GET {booking.base-url}/internal/bookings/active?customerId=..&addressId=..}
 * and interprets a returned booking reference as "address in use". A transport failure is
 * treated conservatively as "not blocking" is unsafe for a delete; therefore any error is
 * surfaced so the caller can fail the deletion rather than silently allow it. Here we log
 * and rethrow-as-empty is avoided â€” instead we propagate by returning the reference only
 * on a definitive positive, and let transport errors bubble up.
 *
 * <p>Activated whenever no other {@link BookingClientPort} bean is present (tests supply
 * their own fake).
 */
@Component
public class HttpBookingClientAdapter implements BookingClientPort {

    private static final Logger log = LoggerFactory.getLogger(HttpBookingClientAdapter.class);

    private final RestClient restClient;

    public HttpBookingClientAdapter(BookingClientProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    @Override
    public Optional<String> findActiveBookingUsingAddress(UUID customerId, UUID addressId) {
        try {
            ActiveBookingResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/bookings/active")
                            .queryParam("customerId", customerId)
                            .queryParam("addressId", addressId)
                            .build())
                    .retrieve()
                    .body(ActiveBookingResponse.class);
            if (response != null && response.bookingReference() != null
                    && !response.bookingReference().isBlank()) {
                return Optional.of(response.bookingReference());
            }
            return Optional.empty();
        } catch (RestClientException e) {
            // Do not log identifiers as PII; a booking reference/customer id are internal
            // identifiers but we keep the message generic per the observability guidelines.
            log.warn("Booking Service active-address check failed");
            throw new BookingLookupException("Unable to verify active bookings for the address", e);
        }
    }

    /** Minimal response shape from the Booking Service internal endpoint. */
    public record ActiveBookingResponse(String bookingReference) {
    }
}
