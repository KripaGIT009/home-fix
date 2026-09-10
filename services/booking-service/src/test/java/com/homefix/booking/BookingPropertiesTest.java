package com.homefix.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingAudit;
import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.domain.JobDurationCalculator;
import com.homefix.booking.domain.JobInterval;
import com.homefix.booking.service.Actor;
import com.homefix.booking.service.BookingTransitionService;
import com.homefix.booking.service.InvalidTransitionException;
import com.homefix.booking.support.InMemoryBookingAuditRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for the Booking Service correctness properties 8-10 (design.md
 * "Correctness Properties"; Requirements 9.1, 9.2, 9.15, 11.6). Each property runs a minimum of
 * 100 tries and is tagged with the required {@code Feature: homefix-platform, Property N} label.
 *
 * <p>These complement the example-based {@code BookingStateMachineTest},
 * {@code BookingTransitionServiceTest}, and {@code JobDurationCalculatorTest} by asserting the
 * properties hold universally across generated inputs.
 */
class BookingPropertiesTest {

    /** Fixed clock so audit timestamps are deterministic. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2024-06-01T10:00:00Z"), ZoneOffset.UTC);
    private static final Instant T0 = Instant.parse("2024-01-01T09:00:00Z");

    private final BookingStateMachine stateMachine = new BookingStateMachine();

    // ============================================================================================
    // Property 8: Booking state machine valid transitions only (Req 9.1, 9.2)
    // ============================================================================================

    @Property(tries = 200)
    @Label("Feature: homefix-platform, Property 8: Booking state machine valid transitions only")
    void transitionPermittedIffTargetInPermittedMapElse409(
            @ForAll("bookingStatus") BookingStatus source,
            @ForAll("bookingStatus") BookingStatus target) {

        InMemoryBookingAuditRepository auditRepo = new InMemoryBookingAuditRepository();
        BookingTransitionService service = new BookingTransitionService(stateMachine, auditRepo, CLOCK);
        Booking booking = bookingIn(source);
        Actor actor = Actor.user(UUID.randomUUID(), "CUSTOMER");

        boolean expectedPermitted = stateMachine.permittedTargets(source).contains(target);

        Throwable thrown = catchThrowable(() -> service.transition(booking, target, actor, "test"));

        if (expectedPermitted) {
            // Permitted: no exception, state advanced, exactly one audit row written.
            assertThat(thrown).as("permitted %s -> %s must not throw", source, target).isNull();
            assertThat(booking.getStatus()).isEqualTo(target);
            assertThat(auditRepo.all()).hasSize(1);
        } else {
            // Not permitted: rejected with a 409-mapped InvalidTransitionException,
            // no state change and no audit row (Property 8 / Req 9.2).
            assertThat(thrown)
                    .as("illegal %s -> %s must be rejected", source, target)
                    .isInstanceOf(InvalidTransitionException.class);
            InvalidTransitionException ex = (InvalidTransitionException) thrown;
            assertThat(ex.getFromState()).isEqualTo(source);
            assertThat(ex.getToState()).isEqualTo(target);
            assertThat(booking.getStatus()).isEqualTo(source);
            assertThat(auditRepo.all()).isEmpty();
        }
    }

    // ============================================================================================
    // Property 9: Booking audit trail completeness (Req 9.15)
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 9: Booking audit trail completeness")
    void appliedTransitionsProduceContiguousAuditChainFromCreated(
            @ForAll("transitionPath") List<BookingStatus> path) {

        InMemoryBookingAuditRepository auditRepo = new InMemoryBookingAuditRepository();
        BookingTransitionService service = new BookingTransitionService(stateMachine, auditRepo, CLOCK);
        UUID actorId = UUID.randomUUID();
        Actor actor = Actor.user(actorId, "CUSTOMER");

        // Booking starts in CREATED; record the opening audit entry (from = null).
        Booking booking = bookingIn(BookingStatus.CREATED);
        service.recordCreation(booking, actor, "created");

        // Apply the generated legal path of transitions; each records exactly one audit entry.
        BookingStatus current = BookingStatus.CREATED;
        int appliedTransitions = 0;
        for (BookingStatus next : path) {
            service.transition(booking, next, actor, "step");
            current = next;
            appliedTransitions++;
        }

        List<BookingAudit> chain = auditRepo.byBooking(booking.getId());

        // Exactly one entry per applied transition, plus the opening CREATED entry.
        assertThat(chain).hasSize(appliedTransitions + 1);

        // The opening entry starts the chain at CREATED with a null from_state.
        BookingAudit first = chain.get(0);
        assertThat(first.getFromState()).isNull();
        assertThat(first.getToState()).isEqualTo(BookingStatus.CREATED);

        // Each subsequent entry has all required fields and its from_state equals the
        // previous entry's to_state — a contiguous chain from CREATED to the current state.
        for (int i = 1; i < chain.size(); i++) {
            BookingAudit entry = chain.get(i);
            assertThat(entry.getBookingId()).isEqualTo(booking.getId());
            assertThat(entry.getActorId()).isEqualTo(actorId);
            assertThat(entry.getActorRole()).isEqualTo("CUSTOMER");
            assertThat(entry.getTransitionedAt()).isNotNull();
            assertThat(entry.getFromState()).isNotNull();
            assertThat(entry.getToState()).isNotNull();
            assertThat(entry.getFromState())
                    .as("entry %d must continue the chain", i)
                    .isEqualTo(chain.get(i - 1).getToState());
        }

        // The final to_state equals the booking's current state.
        assertThat(chain.get(chain.size() - 1).getToState()).isEqualTo(current);
        assertThat(booking.getStatus()).isEqualTo(current);
    }

    // ============================================================================================
    // Property 10: Job duration calculation correctness (Req 11.6)
    // ============================================================================================

    @Property(tries = 200)
    @Label("Feature: homefix-platform, Property 10: Job duration calculation correctness")
    void netDurationEqualsWorkMinusPause(@ForAll("intervalSpecs") List<long[]> specs) {
        UUID bookingId = UUID.randomUUID();
        List<JobInterval> intervals = new ArrayList<>();
        long workSeconds = 0L;
        long pauseSeconds = 0L;
        long cursor = 0L;

        // Build an alternating WORK/PAUSE sequence starting with WORK, closed at each boundary.
        boolean work = true;
        for (long[] spec : specs) {
            long duration = spec[0];
            Instant start = T0.plusSeconds(cursor);
            Instant end = T0.plusSeconds(cursor + duration);
            JobInterval interval = work
                    ? JobInterval.work(bookingId, start)
                    : JobInterval.pause(bookingId, start, "break");
            interval.close(end);
            intervals.add(interval);
            if (work) {
                workSeconds += duration;
            } else {
                pauseSeconds += duration;
            }
            cursor += duration;
            work = !work;
        }

        Instant completedAt = T0.plusSeconds(cursor);
        long expected = Math.max(workSeconds - pauseSeconds, 0L);

        long actual = JobDurationCalculator.netDurationSeconds(intervals, completedAt);

        assertThat(actual)
                .as("net = Σ WORK (%d) − Σ PAUSE (%d), floored at 0", workSeconds, pauseSeconds)
                .isEqualTo(expected);
    }

    // ============================================================================================
    // Generators
    // ============================================================================================

    @Provide
    Arbitrary<BookingStatus> bookingStatus() {
        return Arbitraries.of(BookingStatus.values());
    }

    /**
     * Generates a legal, contiguous path of booking transitions starting from CREATED by walking
     * the state machine's permitted-target map for a bounded number of steps.
     */
    @Provide
    Arbitrary<List<BookingStatus>> transitionPath() {
        return Arbitraries.integers().between(1, 12).flatMap(steps ->
                Arbitraries.randoms().map(random -> {
                    List<BookingStatus> path = new ArrayList<>();
                    BookingStatus current = BookingStatus.CREATED;
                    for (int i = 0; i < steps; i++) {
                        List<BookingStatus> targets = new ArrayList<>(
                                stateMachine.permittedTargets(current));
                        if (targets.isEmpty()) {
                            break; // reached a terminal state
                        }
                        BookingStatus next = targets.get(random.nextInt(targets.size()));
                        path.add(next);
                        current = next;
                    }
                    return path;
                }));
    }

    /**
     * Generates an alternating WORK/PAUSE interval sequence as a list of {@code [durationSeconds]}
     * specs (WORK first). Durations are non-negative and bounded.
     */
    @Provide
    Arbitrary<List<long[]>> intervalSpecs() {
        Arbitrary<Long> duration = Arbitraries.longs().between(0L, 36_000L);
        return duration.map(d -> new long[]{d}).list().ofMinSize(0).ofMaxSize(20);
    }

    // ============================================================================================
    // Helpers
    // ============================================================================================

    private Booking bookingIn(BookingStatus status) {
        Booking b = Booking.create("HFX-20240101-ABC123", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), false, Instant.now(CLOCK),
                new BigDecimal("100.00"));
        b.applyStatus(status);
        return b;
    }
}
