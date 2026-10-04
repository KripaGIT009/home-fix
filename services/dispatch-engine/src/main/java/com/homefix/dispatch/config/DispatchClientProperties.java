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

    /**
     * Base URL of the Notification Service. Not called at present: the Notification Service reacts
     * to events and has no endpoint for the Dispatch Engine (see {@code LoggingNotificationAdapter}).
     * Kept so an adapter for a future contract needs no configuration change.
     */
    private String notificationServiceBaseUrl = "http://notification-service";

    /** Base URL of the Customer Service (booking address to coordinates, Requirement 8.2). */
    private String customerServiceBaseUrl = "http://customer-service";

    /** Base URL of the Service Catalog (subcategory skill tags, Requirement 8.2). */
    private String catalogServiceBaseUrl = "http://catalog-service";

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

    public String getCustomerServiceBaseUrl() {
        return customerServiceBaseUrl;
    }

    public void setCustomerServiceBaseUrl(String customerServiceBaseUrl) {
        this.customerServiceBaseUrl = customerServiceBaseUrl;
    }

    public String getCatalogServiceBaseUrl() {
        return catalogServiceBaseUrl;
    }

    public void setCatalogServiceBaseUrl(String catalogServiceBaseUrl) {
        this.catalogServiceBaseUrl = catalogServiceBaseUrl;
    }
}
