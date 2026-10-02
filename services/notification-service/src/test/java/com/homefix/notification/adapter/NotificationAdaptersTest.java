package com.homefix.notification.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import com.homefix.notification.channel.adapter.LoggingEmailAdapter;
import com.homefix.notification.channel.adapter.LoggingInAppAdapter;
import com.homefix.notification.channel.adapter.LoggingPushAdapter;
import com.homefix.notification.channel.adapter.LoggingSmsAdapter;
import com.homefix.notification.delivery.DeliveryLogEntity;
import com.homefix.notification.delivery.DeliveryLogEntity.DeliveryLogId;
import com.homefix.notification.delivery.DeliveryLogRepository;
import com.homefix.notification.delivery.JpaDeliveryLogStore;
import com.homefix.notification.domain.DeliveryStatus;
import com.homefix.notification.domain.NotificationChannel;
import com.homefix.notification.preference.DefaultAllEnabledPreferenceAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Unit tests for the notification outbound adapters and delivery-log store. The logging channel
 * adapters must accept a dispatch without throwing or logging PII (Requirement 26.4); the default
 * preference adapter reports "unavailable" so the orchestrator treats all channels as enabled
 * (Requirement 17.6); and the JPA delivery-log store swallows a concurrent duplicate insert while
 * still enforcing dedup (Property 22).
 */
class NotificationAdaptersTest {

    @Test
    void loggingChannelAdapters_acceptDispatchWithoutThrowing() {
        assertThatCode(() -> new LoggingSmsAdapter().send("+911", "hi")).doesNotThrowAnyException();
        assertThatCode(() -> new LoggingEmailAdapter().send("a@b.c", "s", "b")).doesNotThrowAnyException();
        assertThatCode(() -> new LoggingPushAdapter().send("token", "t", "b")).doesNotThrowAnyException();
        assertThatCode(() -> new LoggingInAppAdapter().publish(UUID.randomUUID(), "t", "b"))
                .doesNotThrowAnyException();
    }

    @Test
    void defaultPreferenceAdapter_reportsUnavailable() {
        assertThat(new DefaultAllEnabledPreferenceAdapter().findByUserId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void jpaDeliveryLogStore_alreadyDelivered_delegatesToRepository() {
        DeliveryLogRepository repo = mock(DeliveryLogRepository.class);
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(repo.existsByKafkaEventIdAndUserIdAndChannel(eventId, userId, NotificationChannel.SMS))
                .thenReturn(true);

        assertThat(new JpaDeliveryLogStore(repo).alreadyDelivered(eventId, userId, NotificationChannel.SMS))
                .isTrue();
        assertThat(new JpaDeliveryLogStore(repo).alreadyDelivered(eventId, UUID.randomUUID(), NotificationChannel.SMS))
                .as("another recipient of the same event is not a duplicate")
                .isFalse();
    }

    @Test
    void jpaDeliveryLogStore_record_savesAndSwallowsConcurrentDuplicate() {
        DeliveryLogRepository repo = mock(DeliveryLogRepository.class);
        JpaDeliveryLogStore store = new JpaDeliveryLogStore(repo);
        DeliveryLogEntity entry = new DeliveryLogEntity(UUID.randomUUID(), NotificationChannel.EMAIL,
                UUID.randomUUID(), Instant.now(), DeliveryStatus.DELIVERED, 0, null);

        store.record(entry);
        verify(repo).saveAndFlush(entry);

        // A concurrent insert race must be swallowed (dedup guarantee still holds).
        doThrow(new DataIntegrityViolationException("dup")).when(repo).saveAndFlush(any());
        assertThatCode(() -> store.record(entry)).doesNotThrowAnyException();
    }

    @Test
    void jpaDeliveryLogStore_record_commitsInItsOwnTransaction() {
        DeliveryLogRepository repo = mock(DeliveryLogRepository.class);
        PlatformTransactionManager txManager =
                mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(
                new SimpleTransactionStatus(true));
        JpaDeliveryLogStore store = new JpaDeliveryLogStore(repo, txManager);
        DeliveryLogEntity entry = new DeliveryLogEntity(UUID.randomUUID(), NotificationChannel.SMS,
                UUID.randomUUID(), Instant.now(), DeliveryStatus.DELIVERED, 0, null);

        store.record(entry);

        // REQUIRES_NEW: a sent SMS's log row must survive a later failure of the handler that sent it.
        ArgumentCaptor<TransactionDefinition> definition =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(txManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(repo).saveAndFlush(entry);
        verify(txManager).commit(any());
    }

    @Test
    void deliveryLogId_equalsAndHashCode() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        DeliveryLogId a = new DeliveryLogId(eventId, userId, NotificationChannel.PUSH);
        DeliveryLogId b = new DeliveryLogId(eventId, userId, NotificationChannel.PUSH);
        DeliveryLogId c = new DeliveryLogId(UUID.randomUUID(), userId, NotificationChannel.PUSH);
        DeliveryLogId d = new DeliveryLogId(eventId, userId, NotificationChannel.SMS);
        DeliveryLogId e = new DeliveryLogId(eventId, UUID.randomUUID(), NotificationChannel.PUSH);

        assertThat(a).isEqualTo(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c).isNotEqualTo(d).isNotEqualTo(e).isNotEqualTo(null).isNotEqualTo("x");
    }

    @Test
    void deliveryLogEntity_exposesRecordedFields() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        DeliveryLogEntity entry = new DeliveryLogEntity(eventId, NotificationChannel.SMS, userId, now,
                DeliveryStatus.PERMANENTLY_FAILED, 3, "delivery failed");

        assertThat(entry.getKafkaEventId()).isEqualTo(eventId);
        assertThat(entry.getChannel()).isEqualTo(NotificationChannel.SMS);
        assertThat(entry.getUserId()).isEqualTo(userId);
        assertThat(entry.getTimestamp()).isEqualTo(now);
        assertThat(entry.getDeliveryStatus()).isEqualTo(DeliveryStatus.PERMANENTLY_FAILED);
        assertThat(entry.getRetryCount()).isEqualTo(3);
        assertThat(entry.getErrorDescription()).isEqualTo("delivery failed");
    }
}
