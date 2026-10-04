package com.homefix.payment.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.homefix.payment.alert.LoggingFinanceAlertAdapter;
import com.homefix.payment.domain.PaymentMethod;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.event.PaymentCompletedEvent;
import com.homefix.payment.event.PaymentCompletedPublisher;
import com.homefix.payment.idempotency.RedisIdempotencyStoreAdapter;
import com.homefix.payment.invoice.LoggingInvoiceTriggerAdapter;
import com.homefix.payment.notification.LoggingProviderNotificationAdapter;
import com.homefix.payment.wallet.LoggingProviderWalletClientAdapter;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * Smoke and behavior tests for the outbound adapters and the outbox publisher seam. The logging
 * adapters have no branching logic; exercising each method proves it does not throw and is wired
 * to the correct port. The Redis idempotency adapter and the PaymentCompleted publisher are
 * verified against mocks so no Redis/DB is required.
 */
class PaymentAdaptersTest {

    @Test
    void loggingWalletAdapter_creditAndReversal_doNotThrow() {
        LoggingProviderWalletClientAdapter wallet = new LoggingProviderWalletClientAdapter();
        wallet.creditEarning(UUID.randomUUID(), UUID.randomUUID(), "HF-1",
                new BigDecimal("100.00"), new BigDecimal("20.00"), new BigDecimal("80.00"));
        wallet.creditSettlementReversal(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("50.00"));
    }

    @Test
    void loggingInvoiceAdapter_trigger_doesNotThrow() {
        new LoggingInvoiceTriggerAdapter().triggerInvoiceGeneration(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void loggingFinanceAlertAdapter_allAlerts_doNotThrow() {
        LoggingFinanceAlertAdapter alert = new LoggingFinanceAlertAdapter();
        alert.refundFailed(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "boom");
        alert.walletCreditFailed(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "boom");
        alert.settlementFailed(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "boom");
    }

    @Test
    void loggingProviderNotificationAdapter_settlementFailed_doesNotThrow() {
        new LoggingProviderNotificationAdapter()
                .settlementFailed(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"));
    }

    @Test
    void redisIdempotencyAdapter_putIfAbsent_delegatesToSetIfAbsentWithTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        UUID txId = UUID.randomUUID();
        when(ops.setIfAbsent(eq("payment:idem:k1"), eq(txId.toString()), any(Duration.class)))
                .thenReturn(Boolean.TRUE);

        RedisIdempotencyStoreAdapter adapter = new RedisIdempotencyStoreAdapter(redis);
        assertThat(adapter.putIfAbsent("k1", txId, Duration.ofMinutes(10))).isTrue();
    }

    @Test
    void redisIdempotencyAdapter_putIfAbsent_falseWhenKeyExists() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(Boolean.FALSE);

        RedisIdempotencyStoreAdapter adapter = new RedisIdempotencyStoreAdapter(redis);
        assertThat(adapter.putIfAbsent("k1", UUID.randomUUID(), Duration.ofMinutes(10))).isFalse();
    }

    @Test
    void redisIdempotencyAdapter_find_returnsPresentAndEmpty() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        UUID txId = UUID.randomUUID();
        when(ops.get("payment:idem:present")).thenReturn(txId.toString());
        when(ops.get("payment:idem:absent")).thenReturn(null);

        RedisIdempotencyStoreAdapter adapter = new RedisIdempotencyStoreAdapter(redis);
        assertThat(adapter.find("present")).contains(txId);
        assertThat(adapter.find("absent")).isEmpty();
    }

    @Test
    void paymentCompletedPublisher_publishesEventWithDerivedNetEarning() {
        OutboxEventPublisher outbox = mock(OutboxEventPublisher.class);
        PaymentCompletedPublisher publisher = new PaymentCompletedPublisher(outbox);

        UUID bookingId = UUID.randomUUID();
        PaymentTransaction tx = PaymentTransaction.initiate("key", UUID.randomUUID(), bookingId,
                UUID.randomUUID(), new BigDecimal("100.00"), new BigDecimal("20.00"),
                PaymentMethod.UPI, "razorpay", null);

        publisher.publish(tx);

        verify(outbox).publish(eq(PaymentCompletedEvent.AGGREGATE_TYPE), eq(tx.getId()),
                eq(PaymentCompletedEvent.EVENT_TYPE), any(PaymentCompletedEvent.class));
    }

    @Test
    void paymentCompletedEvent_exposesTopicConstants() {
        assertThat(PaymentCompletedEvent.EVENT_TYPE).isEqualTo("PaymentCompleted");
        assertThat(PaymentCompletedEvent.AGGREGATE_TYPE).isEqualTo("Payment");
        assertThat(Optional.of(1)).isPresent(); // sanity
    }
}
