package com.homefix.shared.observability.tracing;

import com.homefix.shared.observability.logging.LogFields;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.MDC;

/**
 * Helper for enriching the current OpenTelemetry span and the SLF4J MDC with
 * business entity identifiers so they surface both in traces and in structured
 * logs (Task 5).
 *
 * <p>Only non-PII identifiers (booking IDs, user IDs, provider IDs, payment IDs)
 * are ever attached. Callers must never pass names, emails, phone numbers, or
 * card data here.
 *
 * <p>Every {@code addX} method both:
 * <ul>
 *   <li>sets the value as an attribute on {@link Span#current()} (a no-op when no
 *       span is recording), and</li>
 *   <li>places it on the MDC so the shared JSON encoder emits it as a field.</li>
 * </ul>
 *
 * <p>Use {@link #clear()} in a {@code finally} block at the end of request/message
 * processing to avoid entity IDs leaking across pooled threads.
 */
public final class TracingSupport {

    public static final String ATTR_BOOKING_ID = "booking.id";
    public static final String ATTR_USER_ID = "user.id";
    public static final String ATTR_PROVIDER_ID = "provider.id";
    public static final String ATTR_PAYMENT_ID = "payment.id";

    private TracingSupport() {
    }

    public static void addBookingId(String bookingId) {
        put(ATTR_BOOKING_ID, LogFields.BOOKING_ID, bookingId);
    }

    public static void addUserId(String userId) {
        put(ATTR_USER_ID, LogFields.USER_ID, userId);
    }

    public static void addProviderId(String providerId) {
        put(ATTR_PROVIDER_ID, LogFields.PROVIDER_ID, providerId);
    }

    public static void addPaymentId(String paymentId) {
        put(ATTR_PAYMENT_ID, LogFields.PAYMENT_ID, paymentId);
    }

    /**
     * Returns the current trace ID from the active span context, or {@code null}
     * when there is no valid span.
     */
    public static String currentTraceId() {
        SpanContext ctx = Span.current().getSpanContext();
        return ctx.isValid() ? ctx.getTraceId() : null;
    }

    /**
     * Returns the current span ID from the active span context, or {@code null}
     * when there is no valid span.
     */
    public static String currentSpanId() {
        SpanContext ctx = Span.current().getSpanContext();
        return ctx.isValid() ? ctx.getSpanId() : null;
    }

    /**
     * Removes all entity-ID keys this helper manages from the MDC.
     */
    public static void clear() {
        MDC.remove(LogFields.BOOKING_ID);
        MDC.remove(LogFields.USER_ID);
        MDC.remove(LogFields.PROVIDER_ID);
        MDC.remove(LogFields.PAYMENT_ID);
    }

    private static void put(String spanAttr, String mdcKey, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        Span.current().setAttribute(spanAttr, value);
        MDC.put(mdcKey, value);
    }
}
