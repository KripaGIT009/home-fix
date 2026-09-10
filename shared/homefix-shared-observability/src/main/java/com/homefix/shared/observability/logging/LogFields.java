package com.homefix.shared.observability.logging;

/**
 * Canonical MDC key names used across all HomeFix services for structured logging.
 *
 * <p>The mandatory fields (timestamp, serviceName, traceId, spanId, logLevel,
 * message) are emitted by the Logback JSON encoder configuration; the optional
 * entity IDs below are placed on the MDC by application code (or the shared
 * tracing helper) so they appear as first-class JSON fields when present.
 */
public final class LogFields {

    /** Logical service name; typically bound from {@code spring.application.name}. */
    public static final String SERVICE_NAME = "serviceName";

    /** Correlation ID propagated across service hops (see shared-security filter). */
    public static final String CORRELATION_ID = "correlationId";

    // ----- Optional entity IDs (never PII) -----
    public static final String BOOKING_ID = "bookingId";
    public static final String USER_ID = "userId";
    public static final String PROVIDER_ID = "providerId";
    public static final String PAYMENT_ID = "paymentId";

    private LogFields() {
    }
}
