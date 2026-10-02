package com.homefix.customer.booking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound from the {@code homefix.booking.*} configuration namespace.
 */
@Component
@ConfigurationProperties(prefix = "homefix.booking")
public class BookingClientProperties {

    /** Base URL of the Booking Service. */
    private String baseUrl = "http://booking-service:8084";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
