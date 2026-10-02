package com.homefix.outbox.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.homefix.outbox.config.OutboxProcessorProperties;
import com.homefix.outbox.relay.OutboxRelayService.RelayOutcome;
import com.homefix.outbox.support.SkipLockedH2Dialect;
import com.homefix.outbox.support.TestSupport.MutableClock;
import com.homefix.outbox.support.TestSupport.RecordingAlertPort;
import com.homefix.shared.outbox.OutboxEventEntity;
import com.homefix.shared.outbox.OutboxEventRepository;
import com.homefix.shared.outbox.OutboxEventStatus;
import org.hibernate.LockOptions;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static com.homefix.outbox.support.TestSupport.producerAlwaysSucceeding;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Row claiming against a real database (Requirement 22.4): the shared repository's
 * {@code claimDue} query through {@link OutboxClaimer}, on H2 in PostgreSQL mode with real
 * transactions, row locks and {@code @Version} checks.
 *
 * <p>H2 2.x executes {@code SELECT ... FOR UPDATE SKIP LOCKED} with row-level locks, but
 * Hibernate 6.4's stock {@code H2Dialect} reports no skip-locked support and silently drops the
 * clause (claimers then block on each other's locks). The test therefore runs on
 * {@link SkipLockedH2Dialect}, which only turns that support flag on; everything else, including
 * how the repository's lock-timeout hint becomes the clause, is stock Hibernate. With it the
 * concurrency here is genuine rather than simulated: a claimer that runs while another claim
 * transaction holds rows gets the other rows, without blocking. PostgreSQL itself is not exercised
 * (no Docker in this build); {@link #postgresDialectRendersTheSameClause()} pins that the stock
 * PostgreSQL dialect supports the clause, and the recorded SQL pins its shape.
 *
 * <p>The ambient test transaction is disabled so every claim commits on its own, as in production.
 */
@DataJpaTest
@ContextConfiguration(classes = OutboxClaimJpaTest.JpaConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.jpa.database-platform=com.homefix.outbox.support.SkipLockedH2Dialect",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.homefix.outbox.relay.OutboxClaimJpaTest$SqlRecorder",
        "spring.datasource.url=jdbc:h2:mem:outbox_claim;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(
        replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxClaimJpaTest {

    private static final Instant T0 = Instant.parse("2024-07-15T10:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(2);

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final MutableClock clock = new MutableClock(T0);
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        SqlRecorder.STATEMENTS.clear();
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void postgresDialectRendersTheSameClause() {
        // The repository's hint value is Hibernate's SKIP_LOCKED lock timeout, and production's
        // dialect renders "skip locked" for it without any help.
        assertThat(OutboxEventRepository.SKIP_LOCKED).isEqualTo(String.valueOf(LockOptions.SKIP_LOCKED));
        assertThat(new PostgreSQLDialect(DatabaseVersion.make(16)).supportsSkipLocked()).isTrue();
    }

    @Test
    void claimQueryLocksWithSkipLocked() {
        insertPending(1);

        claimer(10).claimBatch();

        assertThat(SqlRecorder.STATEMENTS)
                .anySatisfy(sql -> assertThat(sql.toLowerCase())
                        .contains("from outbox.outbox_event")
                        .contains("for update")
                        .contains("skip locked"));
    }

    @Test
    void aClaimRunningWhileAnotherHoldsRowsSkipsThemWithoutWaiting() throws Exception {
        List<UUID> all = insertPending(10);
        CountDownLatch firstHoldsLocks = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // First claimer: selects 4 rows FOR UPDATE and keeps its transaction open.
        Future<List<UUID>> first = executor.submit(() -> tx.execute(status -> {
            List<OutboxEventEntity> rows = repository.claimDue(clock.instant(), PageRequest.of(0, 4));
            firstHoldsLocks.countDown();
            await(secondFinished);
            rows.forEach(r -> r.scheduleNextAttempt(clock.instant().plus(LEASE)));
            return ids(repository.saveAll(rows));
        }));
        assertThat(firstHoldsLocks.await(10, TimeUnit.SECONDS)).isTrue();

        // Second claimer, while those locks are held: must neither block (the 5 s bound; a plain
        // FOR UPDATE waits out the 10 s lock timeout here) nor receive any of the locked rows.
        List<UUID> second;
        try {
            second = executor.submit(() -> ids(claimer(10).claimBatch())).get(5, TimeUnit.SECONDS);
        } finally {
            secondFinished.countDown();
        }
        List<UUID> firstIds = first.get(10, TimeUnit.SECONDS);
        assertThat(firstIds).hasSize(4);
        assertThat(second).doesNotContainAnyElementsOf(firstIds);
        // PostgreSQL locks only the rows a LIMITed query returns, so there the second claim gets the
        // other 6. H2 also locks rows it read but did not return, so here it may get fewer (even
        // none); either way nothing is shared, and whatever it skipped is claimable afterwards.

        List<UUID> third = ids(claimer(10).claimBatch());
        assertThat(java.util.Collections.disjoint(third, firstIds)).isTrue();
        assertThat(java.util.Collections.disjoint(third, second)).isTrue();
        List<UUID> everyClaim = new ArrayList<>(firstIds);
        everyClaim.addAll(second);
        everyClaim.addAll(third);
        assertThat(everyClaim).containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void concurrentClaimersNeverReceiveTheSameRow() throws Exception {
        List<UUID> all = insertPending(60);
        Map<UUID, Integer> timesClaimed = new ConcurrentHashMap<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);

        List<Callable<Void>> claimers = new ArrayList<>();
        for (int c = 0; c < 4; c++) {
            claimers.add(() -> {
                OutboxClaimer claimer = claimer(5);
                await(start);
                int emptyInARow = 0;
                while (emptyInARow < 3) {
                    try {
                        List<OutboxEventEntity> batch = claimer.claimBatch();
                        emptyInARow = batch.isEmpty() ? emptyInARow + 1 : 0;
                        batch.forEach(e -> timesClaimed.merge(e.getId(), 1, Integer::sum));
                    } catch (RuntimeException ex) {
                        errors.add(ex);
                        emptyInARow = 0;
                    }
                }
                return null;
            });
        }
        List<Future<Void>> running = new ArrayList<>();
        claimers.forEach(c -> running.add(executor.submit(c)));
        start.countDown();
        for (Future<Void> f : running) {
            f.get(60, TimeUnit.SECONDS);
        }

        assertThat(errors).as("claim failures (lock waits or version conflicts)").isEmpty();
        assertThat(timesClaimed.keySet()).containsExactlyInAnyOrderElementsOf(all);
        assertThat(timesClaimed.values()).as("times each row was claimed").containsOnly(1);
    }

    @Test
    void onlyDuePendingRowsAreClaimedOldestFirst() {
        UUID dueNever = savedRow(OutboxEventStatus.PENDING, null, 3);
        UUID duePast = savedRow(OutboxEventStatus.PENDING, T0.minusSeconds(1), 2);
        UUID dueNow = savedRow(OutboxEventStatus.PENDING, T0, 1);
        savedRow(OutboxEventStatus.PENDING, T0.plusSeconds(1), 0);
        savedRow(OutboxEventStatus.PUBLISHED, null, 0);
        savedRow(OutboxEventStatus.FAILED, null, 0);

        List<OutboxEventEntity> claimed = claimer(10).claimBatch();

        // Oldest first: the row created earliest (largest age) comes first.
        assertThat(ids(claimed)).containsExactly(dueNever, duePast, dueNow);
        assertThat(claimed).allSatisfy(e -> assertThat(e.getNextAttemptAt()).isEqualTo(T0.plus(LEASE)));
    }

    @Test
    void leaseHidesClaimedRowsUntilItExpiresAndIsPersisted() {
        List<UUID> all = insertPending(3);
        OutboxClaimer claimer = claimer(10);

        assertThat(ids(claimer.claimBatch())).containsExactlyInAnyOrderElementsOf(all);
        assertThat(repository.findById(all.get(0)).orElseThrow().getNextAttemptAt()).isEqualTo(T0.plus(LEASE));

        clock.advance(LEASE.minusSeconds(1));
        assertThat(claimer.claimBatch()).isEmpty();

        clock.advance(Duration.ofSeconds(1));
        assertThat(ids(claimer.claimBatch())).containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void persistedBackoffKeepsAFailedRowOutOfClaimsUntilItIsDue() {
        UUID id = insertPending(1).get(0);
        OutboxEventEntity row = repository.findById(id).orElseThrow();
        row.markFailedAttempt("broker down");
        row.scheduleNextAttempt(T0.plusSeconds(4));
        repository.save(row);

        assertThat(claimer(10).claimBatch()).isEmpty();
        clock.advance(Duration.ofSeconds(4));
        List<OutboxEventEntity> claimed = claimer(10).claimBatch();

        assertThat(ids(claimed)).containsExactly(id);
        assertThat(claimed.get(0).getRetryCount()).isEqualTo(1);
        assertThat(claimed.get(0).getLastError()).isEqualTo("broker down");
    }

    @Test
    void aRelayThatOutlivedItsLeaseCannotOverwriteTheNewOwner() {
        UUID id = insertPending(1).get(0);
        OutboxEventEntity staleClaim = claimer(10).claimBatch().get(0);

        // The lease runs out and a second relay claims the row (bumping its version).
        clock.advance(LEASE.plusSeconds(1));
        OutboxEventEntity freshClaim = claimer(10).claimBatch().get(0);
        assertThat(freshClaim.getVersion()).isGreaterThan(staleClaim.getVersion());

        // The first relay finally gets round to it: it may publish (at-least-once), but its
        // outcome write is rejected and the row keeps the new owner's lease.
        OutboxRelayService relay = relay();
        clock.set(staleClaim.getNextAttemptAt().minus(LEASE)); // as seen by the slow relay
        assertThat(relay.relay(staleClaim)).isEqualTo(RelayOutcome.CLAIM_LOST);
        OutboxEventEntity stored = repository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(stored.getNextAttemptAt()).isEqualTo(freshClaim.getNextAttemptAt());

        // The new owner's outcome is written normally.
        clock.set(T0.plus(LEASE).plusSeconds(1));
        assertThat(relay.relay(freshClaim)).isEqualTo(RelayOutcome.PUBLISHED);
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    // ---------------------------------------------------------------------

    private OutboxClaimer claimer(int batchSize) {
        return new OutboxClaimer(repository, new TransactionTemplate(transactionManager), clock, batchSize, LEASE);
    }

    private OutboxRelayService relay() {
        OutboxProcessorProperties properties = new OutboxProcessorProperties();
        properties.setClaimLease(LEASE);
        properties.getTopics().setDefaultTopic("domain-events");
        return new OutboxRelayService(repository, producerAlwaysSucceeding().producer(),
                new EventTopicResolver(properties.getTopics()), new RecordingAlertPort(), properties, clock);
    }

    private List<UUID> insertPending(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(savedRow(OutboxEventStatus.PENDING, null, count - i));
        }
        return ids;
    }

    /** Saves a row created {@code ageMillis} before {@link #T0}. */
    private UUID savedRow(OutboxEventStatus status, Instant nextAttemptAt, int ageMillis) {
        OutboxEventEntity row = new OutboxEventEntity(UUID.randomUUID(), "Booking", UUID.randomUUID(),
                "BookingCreated", "{\"redacted\":true}");
        ReflectionTestUtils.setField(row, "createdAt", T0.minusMillis(ageMillis));
        row.setStatus(status);
        row.scheduleNextAttempt(nextAttemptAt);
        return repository.save(row).getId();
    }

    private static List<UUID> ids(List<OutboxEventEntity> rows) {
        return rows.stream().map(OutboxEventEntity::getId).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = OutboxEventEntity.class)
    @EnableJpaRepositories(basePackageClasses = OutboxEventRepository.class)
    static class JpaConfig {
    }

    /** Records every SQL statement Hibernate prepares, to pin the claim query's locking clause. */
    public static class SqlRecorder implements StatementInspector {
        static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

        @Override
        public String inspect(String sql) {
            STATEMENTS.add(sql);
            return sql;
        }
    }
}
