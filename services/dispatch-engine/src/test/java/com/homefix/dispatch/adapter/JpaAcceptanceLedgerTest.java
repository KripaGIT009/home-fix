package com.homefix.dispatch.adapter;

import com.homefix.dispatch.domain.PendingAcceptance;
import com.homefix.dispatch.domain.PendingAcceptanceRepository;
import com.homefix.dispatch.service.ProviderAcceptedPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ledger writes {@code ProviderAccepted} only for the call that removed the pending row, and
 * spaces retries out (Requirement 8.6).
 */
class JpaAcceptanceLedgerTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

    private PendingAcceptanceRepository repository;
    private ProviderAcceptedPublisher publisher;
    private JpaAcceptanceLedger ledger;

    private final UUID booking = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(PendingAcceptanceRepository.class);
        publisher = mock(ProviderAcceptedPublisher.class);
        ledger = new JpaAcceptanceLedger(repository, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PendingAcceptance pending() {
        return new PendingAcceptance(booking, customer, provider, NOW.minusSeconds(600), NOW, NOW);
    }

    @Test
    void openLeasesTheNewEntryToTheDispatchThread() {
        ledger.open(booking, customer, provider, NOW.minusSeconds(600));

        ArgumentCaptor<PendingAcceptance> saved = ArgumentCaptor.forClass(PendingAcceptance.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getProviderId()).isEqualTo(provider);
        assertThat(saved.getValue().isBookingAccepted()).isFalse();
        assertThat(saved.getValue().getNextAttemptAt()).isEqualTo(NOW.plus(JpaAcceptanceLedger.LEASE));
    }

    @Test
    void announceWritesTheEventWhenItRemovedTheEntry() {
        when(repository.findById(booking)).thenReturn(Optional.of(pending()));
        when(repository.deleteByBookingIdReturningCount(booking)).thenReturn(1);

        assertThat(ledger.announce(booking)).isTrue();

        verify(publisher).publish(booking, customer, provider, NOW.minusSeconds(600));
    }

    @Test
    void announceWritesNothingWhenAnotherRunnerRemovedTheEntryFirst() {
        when(repository.findById(booking)).thenReturn(Optional.of(pending()));
        when(repository.deleteByBookingIdReturningCount(booking)).thenReturn(0);

        assertThat(ledger.announce(booking)).isFalse();

        verify(publisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    void announceWithoutAnEntryWritesNothing() {
        when(repository.findById(booking)).thenReturn(Optional.empty());

        assertThat(ledger.announce(booking)).isFalse();

        verify(publisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    void postponeCountsTheFailureAndBacksOff() {
        PendingAcceptance entry = pending();
        when(repository.findById(booking)).thenReturn(Optional.of(entry));

        ledger.postpone(booking, "503");

        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(entry.getLastError()).isEqualTo("503");
        assertThat(entry.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void backoffDoublesUpToTenMinutes() {
        assertThat(JpaAcceptanceLedger.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(JpaAcceptanceLedger.backoff(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(JpaAcceptanceLedger.backoff(5)).isEqualTo(Duration.ofMinutes(8));
        assertThat(JpaAcceptanceLedger.backoff(6)).isEqualTo(Duration.ofMinutes(10));
        assertThat(JpaAcceptanceLedger.backoff(1_000)).isEqualTo(Duration.ofMinutes(10));
    }
}
