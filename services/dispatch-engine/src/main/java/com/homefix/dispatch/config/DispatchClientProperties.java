package com.homefix.dispatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Base URLs for the downstream services the Dispatch Engine calls over HTTP. Injected into the
 * default outbound adapters; overridden per environment.
 */
@ConfigurationProperties(prefix = "homefix.dispatch.clients")
public class DispatchClientProperties {

    /** Base URL of the Provider Service (eligible-provider queries). */
    private String providerServiceBaseUrl = "http://provider-service";

    /** Base URL of the Booking Service (state transitions). */
    private String bookingServiceBaseUrl = "http://booking-service";

    /** Base URL of the Notification Service (customer/dispatcher alerts). */
    private String notificationServiceBaseUrl = "http://notification-service";

    public String getProviderServiceBaseUrl() {
        return providerServiceBaseUrl;
    }

    public void setProviderServiceBaseUrl(String providerServiceBaseUrl) {
        this.providerServiceBaseUrl = providerServiceBaseUrl;
    }

    public String getBookingServiceBaseUrl() {
        return bookingServiceBaseUrl;
    }

    public void setBookingServiceBaseUrl(String bookingServiceBaseUrl) {
        this.bookingServiceBaseUrl = bookingServiceBaseUrl;
    }

    public String getNotificationServiceBaseUrl() {
        return notificationServiceBaseUrl;
    }

    public void setNotificationServiceBaseUrl(String notificationServiceBaseUrl) {
        this.notificationServiceBaseUrl = notificationServiceBaseUrl;
    }
}
