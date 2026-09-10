package com.homefix.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the booking persistence entities' factory methods, accessors, and derived
 * values (design {@code BOOKING}, {@code JOB_INTERVAL}, {@code JOB_MEDIA}, {@code PARTS_LINE_ITEM},
 * {@code SAGA_STEP}, {@code BOOKING_AUDIT}). These entities carry small amounts of behaviour
 * (line totals, interval close/open state, saga lifecycle) worth covering directly.
 */
class BookingDomainEntityTest {

    @Test
    void bookingCreateInitialisesCreatedStateAndFields() {
        UUID customer = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID sub = UUID.randomUUID();
        UUID address = UUID.randomUUID();
        Instant scheduled = Instant.parse("2024-06-15T10:00:00Z");

        Booking b = Booking.create("HFX-1", customer, category, sub, address, true, scheduled,
                new BigDecimal("100.00"));

        assertThat(b.getId()).isNotNull();
        assertThat(b.getReference()).isEqualTo("HFX-1");
        assertThat(b.getCustomerId()).isEqualTo(customer);
        assertThat(b.getCategoryId()).isEqualTo(category);
        assertThat(b.getSubcategoryId()).isEqualTo(sub);
        assertThat(b.getAddressId()).isEqualTo(address);
        assertThat(b.isEmergency()).isTrue();
        assertThat(b.getScheduledAt()).isEqualTo(scheduled);
        assertThat(b.getEstimatedTotal()).isEqualByComparingTo("100.00");
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CREATED);
        assertThat(b.getCreatedAt()).isNotNull();
        assertThat(b.getVersion()).isZero();
    }

    @Test
    void bookingMutatorsRoundTrip() {
        Booking b = Booking.create("HFX-2", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), false, null, new BigDecimal("50.00"));

        UUID provider = UUID.randomUUID();
        Instant started = Instant.parse("2024-06-15T11:00:00Z");
        Instant completed = Instant.parse("2024-06-15T12:00:00Z");
        b.setProviderId(provider);
        b.applyStatus(BookingStatus.JOB_STARTED);
        b.setFinalTotal(new BigDecimal("75.00"));
        b.setCancellationFee(new BigDecimal("5.00"));
        b.setSagaState("SEARCHING");
        b.setStartedAt(started);
        b.setCompletedAt(completed);
        b.setNetDurationSeconds(3600);

        assertThat(b.getProviderId()).isEqualTo(provider);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.JOB_STARTED);
        assertThat(b.getFinalTotal()).isEqualByComparingTo("75.00");
        assertThat(b.getCancellationFee()).isEqualByComparingTo("5.00");
        assertThat(b.getSagaState()).isEqualTo("SEARCHING");
        assertThat(b.getStartedAt()).isEqualTo(started);
        assertThat(b.getCompletedAt()).isEqualTo(completed);
        assertThat(b.getNetDurationSeconds()).isEqualTo(3600);
    }

    @Test
    void jobIntervalWorkIsOpenUntilClosed() {
        UUID booking = UUID.randomUUID();
        Instant start = Instant.parse("2024-06-15T10:00:00Z");
        JobInterval work = JobInterval.work(booking, start);

        assertThat(work.getKind()).isEqualTo(JobInterval.Kind.WORK);
        assertThat(work.getBookingId()).isEqualTo(booking);
        assertThat(work.getStartedAt()).isEqualTo(start);
        assertThat(work.getReason()).isNull();
        assertThat(work.isOpen()).isTrue();
        assertThat(work.getId()).isNotNull();

        Instant end = start.plusSeconds(600);
        work.close(end);
        assertThat(work.isOpen()).isFalse();
        assertThat(work.getEndedAt()).isEqualTo(end);
    }

    @Test
    void jobIntervalPauseCarriesReason() {
        JobInterval pause = JobInterval.pause(UUID.randomUUID(),
                Instant.parse("2024-06-15T10:10:00Z"), "waiting for part");

        assertThat(pause.getKind()).isEqualTo(JobInterval.Kind.PAUSE);
        assertThat(pause.getReason()).isEqualTo("waiting for part");
        assertThat(pause.isOpen()).isTrue();
    }

    @Test
    void jobMediaOfCapturesMetadata() {
        UUID booking = UUID.randomUUID();
        JobMedia media = JobMedia.of(booking, "BEFORE_PHOTO", "image/jpeg", 1234L, "bookings/x/media/y");

        assertThat(media.getId()).isNotNull();
        assertThat(media.getBookingId()).isEqualTo(booking);
        assertThat(media.getType()).isEqualTo("BEFORE_PHOTO");
        assertThat(media.getContentType()).isEqualTo("image/jpeg");
        assertThat(media.getSizeBytes()).isEqualTo(1234L);
        assertThat(media.getS3Key()).isEqualTo("bookings/x/media/y");
        assertThat(media.getUploadedAt()).isNotNull();
    }

    @Test
    void partsLineItemComputesLineTotal() {
        UUID booking = UUID.randomUUID();
        Instant at = Instant.parse("2024-06-15T10:00:00Z");
        PartsLineItem item = PartsLineItem.of(booking, "Valve", 3, new BigDecimal("12.50"), at);

        assertThat(item.getId()).isNotNull();
        assertThat(item.getBookingId()).isEqualTo(booking);
        assertThat(item.getItemName()).isEqualTo("Valve");
        assertThat(item.getQuantity()).isEqualTo(3);
        assertThat(item.getUnitCost()).isEqualByComparingTo("12.50");
        assertThat(item.getAddedAt()).isEqualTo(at);
        assertThat(item.lineTotal()).isEqualByComparingTo("37.50");
    }

    @Test
    void sagaStepLifecycleTransitions() {
        UUID booking = UUID.randomUUID();
        SagaStep step = SagaStep.started(booking, 1, "persist");

        assertThat(step.getId()).isNotNull();
        assertThat(step.getBookingId()).isEqualTo(booking);
        assertThat(step.getSequenceNo()).isEqualTo(1);
        assertThat(step.getStepName()).isEqualTo("persist");
        assertThat(step.getStatus()).isEqualTo(SagaStep.Status.STARTED);
        assertThat(step.getRecordedAt()).isNotNull();

        step.markCompleted();
        assertThat(step.getStatus()).isEqualTo(SagaStep.Status.COMPLETED);
        assertThat(step.getCompletedAt()).isNotNull();

        SagaStep failed = SagaStep.started(booking, 2, "publish");
        failed.markFailed("kafka down");
        assertThat(failed.getStatus()).isEqualTo(SagaStep.Status.FAILED);
        assertThat(failed.getDetail()).isEqualTo("kafka down");

        SagaStep compensated = SagaStep.started(booking, 3, "estimate");
        compensated.markCompensated();
        assertThat(compensated.getStatus()).isEqualTo(SagaStep.Status.COMPENSATED);
        assertThat(compensated.getCompensatedAt()).isNotNull();
    }

    @Test
    void sagaStepTruncatesOverlongDetail() {
        SagaStep step = SagaStep.started(UUID.randomUUID(), 1, "x");
        step.markFailed("a".repeat(2500));

        assertThat(step.getDetail()).hasSize(2000);
    }

    @Test
    void bookingAuditRecordsTransitionTuple() {
        UUID booking = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        Instant at = Instant.parse("2024-06-15T10:00:00Z");
        BookingAudit audit = BookingAudit.of(booking, BookingStatus.CREATED,
                BookingStatus.SEARCHING_PROVIDER, actor, "CUSTOMER", at, "confirmed");

        assertThat(audit.getId()).isNotNull();
        assertThat(audit.getBookingId()).isEqualTo(booking);
        assertThat(audit.getFromState()).isEqualTo(BookingStatus.CREATED);
        assertThat(audit.getToState()).isEqualTo(BookingStatus.SEARCHING_PROVIDER);
        assertThat(audit.getActorId()).isEqualTo(actor);
        assertThat(audit.getActorRole()).isEqualTo("CUSTOMER");
        assertThat(audit.getTransitionedAt()).isEqualTo(at);
        assertThat(audit.getReason()).isEqualTo("confirmed");
    }
}
