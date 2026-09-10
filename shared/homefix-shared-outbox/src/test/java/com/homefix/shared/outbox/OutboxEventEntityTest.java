package com.homefix.shared.outbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link OutboxEventEntity} lifecycle transitions (Task 6): a new row starts
 * PENDING with a zero retry count, {@code markPublished} stamps the publication time, failed
 * attempts increment the retry counter, and {@code markFailed} is terminal. Overlong error text
 * is truncated to fit the column.
 */
class OutboxEventEntityTest {

    private static final UUID AGG = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void newEventStartsPendingWithZeroRetries() {
        OutboxEventEntity event =
                OutboxEventEntity.newEvent("Booking", AGG, "BookingCreated", "{\"a\":1}");

        assertThat(event.getId()).isNotNull();
        assertThat(event.getAggregateType()).isEqualTo("Booking");
        assertThat(event.getAggregateId()).isEqualTo(AGG);
        assertThat(event.getEventType()).isEqualTo("BookingCreated");
        assertThat(event.getPayload()).isEqualTo("{\"a\":1}");
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getLastError()).isNull();
        assertThat(event.getVersion()).isZero();
    }

    @Test
    void markPublishedStampsTimeAndClearsError() {
        OutboxEventEntity event =
                OutboxEventEntity.newEvent("Booking", AGG, "BookingCreated", "{}");
        event.markFailedAttempt("transient");
        Instant when = Instant.now();

        event.markPublished(when);

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(when);
        assertThat(event.getLastError()).isNull();
    }

    @Test
    void markFailedAttemptIncrementsRetryCountAndRecordsError() {
        OutboxEventEntity event =
                OutboxEventEntity.newEvent("Booking", AGG, "BookingCreated", "{}");

        event.markFailedAttempt("boom-1");
        event.markFailedAttempt("boom-2");

        assertThat(event.getRetryCount()).isEqualTo(2);
        assertThat(event.getLastError()).isEqualTo("boom-2");
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
    }

    @Test
    void markFailedIsTerminal() {
        OutboxEventEntity event =
                OutboxEventEntity.newEvent("Booking", AGG, "BookingCreated", "{}");

        event.markFailed("permanent failure");

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(event.getLastError()).isEqualTo("permanent failure");
    }

    @Test
    void overlongErrorIsTruncatedToColumnLength() {
        OutboxEventEntity event =
                OutboxEventEntity.newEvent("Booking", AGG, "BookingCreated", "{}");

        event.markFailed("x".repeat(5000));

        assertThat(event.getLastError()).hasSize(2000);
    }

    @Test
    void setStatusUpdatesStatus() {
        OutboxEventEntity event =
                OutboxEventEntity.newEvent("Booking", AGG, "BookingCreated", "{}");

        event.setStatus(OutboxEventStatus.PUBLISHED);

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
    }
}
