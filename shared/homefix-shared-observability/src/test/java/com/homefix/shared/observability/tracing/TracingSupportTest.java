package com.homefix.shared.observability.tracing;

import com.homefix.shared.observability.logging.LogFields;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TracingSupportTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    private final Tracer tracer = otel.getOpenTelemetry().getTracer("test");

    @AfterEach
    void tearDown() {
        TracingSupport.clear();
    }

    @Test
    void addBookingId_setsSpanAttributeAndMdc() {
        Span span = tracer.spanBuilder("op").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            TracingSupport.addBookingId("BKG-1");
            TracingSupport.addUserId("USR-2");
            assertThat(MDC.get(LogFields.BOOKING_ID)).isEqualTo("BKG-1");
            assertThat(MDC.get(LogFields.USER_ID)).isEqualTo("USR-2");
        } finally {
            span.end();
        }

        List<SpanData> spans = otel.getSpans();
        assertThat(spans).hasSize(1);
        SpanData data = spans.get(0);
        assertThat(data.getAttributes().asMap().toString())
                .contains("booking.id").contains("BKG-1")
                .contains("user.id").contains("USR-2");
    }

    @Test
    void currentTraceAndSpanIds_availableWithinSpan() {
        Span span = tracer.spanBuilder("op2").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            assertThat(TracingSupport.currentTraceId()).isNotNull().hasSize(32);
            assertThat(TracingSupport.currentSpanId()).isNotNull().hasSize(16);
        } finally {
            span.end();
        }
    }

    @Test
    void currentIds_areNullWithoutActiveSpan() {
        // No span in scope -> invalid span context.
        assertThat(TracingSupport.currentTraceId()).isNull();
        assertThat(TracingSupport.currentSpanId()).isNull();
    }

    @Test
    void blankOrNullValues_areIgnored() {
        Span span = tracer.spanBuilder("op3").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            TracingSupport.addProviderId("");
            TracingSupport.addPaymentId(null);
            assertThat(MDC.get(LogFields.PROVIDER_ID)).isNull();
            assertThat(MDC.get(LogFields.PAYMENT_ID)).isNull();
        } finally {
            span.end();
        }
    }

    @Test
    void clear_removesAllEntityIdsFromMdc() {
        Span span = tracer.spanBuilder("op4").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            TracingSupport.addBookingId("BKG-9");
            TracingSupport.addProviderId("PRV-9");
            TracingSupport.clear();
            assertThat(MDC.get(LogFields.BOOKING_ID)).isNull();
            assertThat(MDC.get(LogFields.PROVIDER_ID)).isNull();
        } finally {
            span.end();
        }
    }
}
