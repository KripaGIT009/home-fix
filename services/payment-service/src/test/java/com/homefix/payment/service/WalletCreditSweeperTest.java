package com.homefix.payment.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WalletCreditSweeper}: it delegates to
 * {@link PaymentService#retryPendingWalletCredits()} and never lets a failed sweep escape into the
 * scheduler thread, so the next tick still runs. The sweep logic itself is covered in
 * {@code PaymentServiceTest}.
 */
class WalletCreditSweeperTest {

    @Test
    void sweep_delegatesToTheService() {
        PaymentService service = mock(PaymentService.class);
        when(service.retryPendingWalletCredits()).thenReturn(2);

        new WalletCreditSweeper(service).sweep();

        verify(service).retryPendingWalletCredits();
    }

    @Test
    void failedSweep_isSwallowedSoTheNextTickRuns() {
        PaymentService service = mock(PaymentService.class);
        when(service.retryPendingWalletCredits()).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> new WalletCreditSweeper(service).sweep()).doesNotThrowAnyException();
    }
}
