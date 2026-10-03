package com.homefix.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.homefix.booking.catalog.CatalogClientPort;
import com.homefix.booking.config.BookingProperties;
import com.homefix.booking.service.Actor;
import com.homefix.booking.service.AssignmentTimeoutSweeper;
import com.homefix.booking.service.BookingException;
import com.homefix.booking.service.BookingLifecycleEventPublisher;
import com.homefix.booking.service.BookingQueryService;
import com.homefix.booking.service.BookingTransitionService;
import com.homefix.booking.service.CallerTenantResolver;
import com.homefix.booking.service.ProviderAssignmentService;
import com.homefix.booking.service.TenantBookingService;
import com.homefix.booking.support.Bookings;
import com.homefix.booking.support.FakeTenantDirectory;
import com.homefix.booking.tenant.TenantDirectoryPort.TenantSummary;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * Races between real transactions on a real (H2, PostgreSQL-mode) database: two Tenant_Admins
 * assigning the same booking (Requirement MT-5.4, Property MT3) and two sweeper instances expiring
 * the same booking (Requirement MT-7.3), and a Provider's acceptance racing the assignment deadline
 * (Property MT6). All are settled by the booking's optimistic lock, which an
 * in-memory fake cannot show. Also runs the Tenant queue and job-list queries through the
 * persistence stack.
 *
 * <p>The test itself runs outside a transaction ({@code NOT_SUPPORTED}) so each worker thread's
 * transaction commits for real and the other can see it, as two service instances would.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:booking_tenant;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
                + "INIT=CREATE SCHEMA IF NOT EXISTS outbox",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.default_schema="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantAssignmentConcurrencyTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private BookingTenantCandidateRepository candidateRepository;

    @Autowired
    private BookingAuditRepository auditRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;
    private FakeTenantDirectory directory;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        directory = new FakeTenantDirectory();
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private BookingTransitionService transitions(BookingLifecycleEventPublisher publisher) {
        return new BookingTransitionService(new BookingStateMachine(), auditRepository, publisher, CLOCK);
    }

    private BookingLifecycleEventPublisher plainPublisher() {
        return new BookingLifecycleEventPublisher(mock(OutboxEventPublisher.class), CLOCK);
    }

    private TenantBookingService tenantBookings() {
        CatalogClientPort catalog = new CatalogClientPort() {
            @Override
            public boolean isSubcategoryActive(UUID categoryId, UUID subcategoryId) {
                return true;
            }

            @Override
            public Map<UUID, String> subcategoryNames() {
                return Map.of();
            }
        };
        BookingQueryService queries = new BookingQueryService(bookingRepository, catalog,
                mock(JobMediaRepository.class), mock(PartsLineItemRepository.class), id -> Optional.empty());
        return new TenantBookingService(bookingRepository, candidateRepository,
                new CallerTenantResolver(directory, CLOCK), directory, transitions(plainPublisher()),
                queries, id -> Optional.empty(), transactions);
    }

    /** Persists a booking queued for the given Tenants and returns its id. */
    private UUID queued(Instant queuedAt, UUID... tenantIds) {
        return transactions.execute(tx -> {
            Booking booking = Bookings.placed(UUID.randomUUID(), UUID.randomUUID(),
                    "HFX-" + UUID.randomUUID().toString().substring(0, 13), Instant.now(CLOCK), null);
            booking.applyStatus(BookingStatus.AWAITING_ASSIGNMENT);
            booking.setQueuedForAssignmentAt(queuedAt);
            Booking saved = bookingRepository.save(booking);
            for (UUID tenantId : tenantIds) {
                candidateRepository.save(new BookingTenantCandidate(saved.getId(), tenantId));
            }
            return saved.getId();
        });
    }

    private long auditRowsInto(UUID bookingId, BookingStatus state) {
        return auditRepository.findAll().stream()
                .filter(a -> a.getBookingId().equals(bookingId) && a.getToState() == state)
                .count();
    }

    @Test
    void ofTwoConcurrentAssignmentsExactlyOneWinsAndTheOtherGets409() throws Exception {
        TenantSummary ara = directory.addTenant("Ara Home Services", "ACTIVE");
        TenantSummary bhojpur = directory.addTenant("Bhojpur Fixers", "ACTIVE");
        UUID araAdmin = UUID.randomUUID();
        UUID bhojpurAdmin = UUID.randomUUID();
        UUID araProvider = UUID.randomUUID();
        UUID bhojpurProvider = UUID.randomUUID();
        directory.adminToTenant.put(araAdmin, ara.tenantId());
        directory.adminToTenant.put(bhojpurAdmin, bhojpur.tenantId());
        directory.addMember(ara.tenantId(), araProvider, true);
        directory.addMember(bhojpur.tenantId(), bhojpurProvider, true);
        UUID bookingId = queued(Instant.now(CLOCK), ara.tenantId(), bhojpur.tenantId());

        // Both admins have read the booking (still waiting, same version) before either writes.
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        directory.onMembership = () -> await(bothChecked);
        TenantBookingService service = tenantBookings();

        Future<Object> araAttempt = pool.submit(attempt(() ->
                service.assign(araAdmin, bookingId.toString(), araProvider)));
        Future<Object> bhojpurAttempt = pool.submit(attempt(() ->
                service.assign(bhojpurAdmin, bookingId.toString(), bhojpurProvider)));
        List<Object> outcomes = List.of(araAttempt.get(30, TimeUnit.SECONDS), bhojpurAttempt.get(30, TimeUnit.SECONDS));

        List<BookingException> refusals = outcomes.stream()
                .filter(BookingException.class::isInstance).map(BookingException.class::cast).toList();
        assertThat(refusals).as("outcomes %s", outcomes).hasSize(1);
        assertThat(refusals.get(0).getErrorCode()).isEqualTo("BOOKING_NOT_ASSIGNABLE");
        assertThat(refusals.get(0).getStatus().value()).isEqualTo(409);

        Booking stored = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(BookingStatus.PROVIDER_ASSIGNED);
        boolean araWon = outcomes.get(0) instanceof TenantBookingService.TenantBookingView;
        assertThat(stored.getTenantId()).isEqualTo(araWon ? ara.tenantId() : bhojpur.tenantId());
        assertThat(stored.getProviderId()).isEqualTo(araWon ? araProvider : bhojpurProvider);
        // The loser left nothing behind: one transition, one audit row.
        assertThat(auditRowsInto(bookingId, BookingStatus.PROVIDER_ASSIGNED)).isEqualTo(1);
    }

    @Test
    void twoSweepersExpiringTheSameBookingTransitionItOnce() throws Exception {
        UUID bookingId = queued(Instant.now(CLOCK).minus(Duration.ofHours(2)), UUID.randomUUID());
        BookingProperties properties = new BookingProperties();
        properties.setTenantAssignmentTimeout(Duration.ofMinutes(60));

        // Hold both instances inside their transition until each has applied it, so both writes
        // race for the same row version.
        CyclicBarrier bothApplied = new CyclicBarrier(2);
        AtomicBoolean released = new AtomicBoolean();
        BookingLifecycleEventPublisher racingPublisher = new BookingLifecycleEventPublisher(
                mock(OutboxEventPublisher.class), CLOCK) {
            @Override
            public void onTransition(Booking booking, BookingStatus from, BookingStatus to,
                                     Actor actor, String reason, String tenantName) {
                if (!released.get()) {
                    await(bothApplied);
                    released.set(true);
                }
                super.onTransition(booking, from, to, actor, reason, tenantName);
            }
        };
        AssignmentTimeoutSweeper first = new AssignmentTimeoutSweeper(bookingRepository,
                transitions(racingPublisher), transactions, properties, CLOCK);
        AssignmentTimeoutSweeper second = new AssignmentTimeoutSweeper(bookingRepository,
                transitions(racingPublisher), transactions, properties, CLOCK);

        Future<Integer> a = pool.submit(first::sweep);
        Future<Integer> b = pool.submit(second::sweep);
        int failed = a.get(30, TimeUnit.SECONDS) + b.get(30, TimeUnit.SECONDS);

        assertThat(failed).isEqualTo(1);
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.SEARCHING_FAILED);
        assertThat(auditRowsInto(bookingId, BookingStatus.SEARCHING_FAILED)).isEqualTo(1);
    }

    @Test
    void queueAndTenantListQueriesRunOnTheDatabase() {
        UUID tenant = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Instant now = Instant.now(CLOCK);
        UUID later = queued(now.minusSeconds(60), tenant, other);
        UUID earlier = queued(now.minusSeconds(600), tenant);
        UUID othersOnly = queued(now.minusSeconds(300), other);
        UUID declinedToOther = queued(now.minusSeconds(900), tenant, other);
        transactions.executeWithoutResult(tx -> {
            Booking b = bookingRepository.findById(declinedToOther).orElseThrow();
            b.setTenantId(other);
        });

        assertThat(bookingRepository.findAssignmentQueue(tenant, PageRequest.of(0, 10)))
                .extracting(Booking::getId).containsExactly(earlier, later);
        assertThat(bookingRepository.findAssignmentQueue(other, PageRequest.of(0, 10)))
                .extracting(Booking::getId).containsExactly(declinedToOther, othersOnly, later);
        assertThat(bookingRepository.findByTenantIdAndStatusInOrderByCreatedAtDescIdDesc(
                other, EnumSet.of(BookingStatus.AWAITING_ASSIGNMENT), PageRequest.of(0, 10)))
                .extracting(Booking::getId).containsExactly(declinedToOther);
        UUID unconfirmed = queued(now.minusSeconds(1200), tenant);
        transactions.executeWithoutResult(tx ->
                bookingRepository.findById(unconfirmed).orElseThrow().applyStatus(BookingStatus.PROVIDER_ASSIGNED));
        assertThat(bookingRepository.findByStatusInAndQueuedForAssignmentAtBeforeOrderByQueuedForAssignmentAtAsc(
                EnumSet.of(BookingStatus.AWAITING_ASSIGNMENT, BookingStatus.PROVIDER_ASSIGNED),
                now.minusSeconds(250), PageRequest.of(0, 10)))
                .extracting(Booking::getId)
                .containsSubsequence(unconfirmed, declinedToOther, earlier, othersOnly)
                .doesNotContain(later);
    }

    @Test
    void anAcceptanceRacingTheDeadlineSweepLetsExactlyOneWin() throws Exception {
        UUID provider = UUID.randomUUID();
        UUID bookingId = queued(Instant.now(CLOCK).minus(Duration.ofHours(2)), UUID.randomUUID());
        transactions.executeWithoutResult(tx -> {
            Booking b = bookingRepository.findById(bookingId).orElseThrow();
            b.setProviderId(provider);
            b.applyStatus(BookingStatus.PROVIDER_ASSIGNED);
        });
        BookingProperties properties = new BookingProperties();
        properties.setTenantAssignmentTimeout(Duration.ofMinutes(60));

        // The Provider's acceptance and the sweep each reach their state-driven step only after both
        // have read the booking at the same version.
        CyclicBarrier bothApplied = new CyclicBarrier(2);
        java.util.concurrent.atomic.AtomicInteger arrivals = new java.util.concurrent.atomic.AtomicInteger();
        BookingLifecycleEventPublisher racingPublisher = new BookingLifecycleEventPublisher(
                mock(OutboxEventPublisher.class), CLOCK) {
            @Override
            public void onTransition(Booking booking, BookingStatus from, BookingStatus to,
                                     Actor actor, String reason, String tenantName) {
                if (arrivals.incrementAndGet() <= 2) {
                    await(bothApplied);
                }
                super.onTransition(booking, from, to, actor, reason, tenantName);
            }
        };
        BookingTransitionService racing = transitions(racingPublisher);
        ProviderAssignmentService assignments = new ProviderAssignmentService(bookingRepository, racing,
                mock(OutboxEventPublisher.class), CLOCK);
        AssignmentTimeoutSweeper sweeper = new AssignmentTimeoutSweeper(bookingRepository, racing,
                transactions, properties, CLOCK);

        Future<Object> acceptance = pool.submit(() -> {
            try {
                return transactions.execute(tx -> assignments.accept(bookingId.toString(), provider));
            } catch (RuntimeException e) {
                return e;
            }
        });
        Future<Integer> sweep = pool.submit(sweeper::sweep);
        Object accepted = acceptance.get(30, TimeUnit.SECONDS);
        int swept = sweep.get(30, TimeUnit.SECONDS);

        boolean acceptWon = accepted instanceof Booking;
        assertThat(acceptWon ^ swept == 1).as("accept=%s swept=%d", accepted, swept).isTrue();
        Booking stored = bookingRepository.findById(bookingId).orElseThrow();
        if (acceptWon) {
            assertThat(stored.getStatus()).isEqualTo(BookingStatus.PROVIDER_ACCEPTED);
            assertThat(auditRowsInto(bookingId, BookingStatus.SEARCHING_FAILED)).isZero();
            assertThat(auditRowsInto(bookingId, BookingStatus.AWAITING_ASSIGNMENT)).isZero();
        } else {
            // The losing acceptance changed nothing and reaches the client as 409 BOOKING_CHANGED.
            assertThat(accepted).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
            assertThat(stored.getStatus()).isEqualTo(BookingStatus.SEARCHING_FAILED);
            assertThat(auditRowsInto(bookingId, BookingStatus.PROVIDER_ACCEPTED)).isZero();
            assertThat(auditRowsInto(bookingId, BookingStatus.SEARCHING_FAILED)).isEqualTo(1);
        }
    }

    private static Callable<Object> attempt(Callable<Object> action) {
        return () -> {
            try {
                return action.call();
            } catch (BookingException e) {
                return e;
            }
        };
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("barrier", e);
        }
    }
}
