package com.homefix.notification.delivery;

import java.util.UUID;

import com.homefix.notification.domain.NotificationChannel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JPA-backed {@link DeliveryLogStore}. The uniqueness of the
 * {@code (kafkaEventId, userId, channel)} primary key is what enforces deduplication at the storage layer, so even a concurrent
 * redelivery cannot double-insert (Property 22).
 *
 * <p>Each entry commits in its own transaction. The shared idempotent consumer runs the whole
 * handler inside the transaction that records the event as processed, so without this a handler
 * that failed after some recipients were already sent to would roll their log rows back with it,
 * and the retry would send to them again. An SMS or push cannot be rolled back; its log row must
 * outlive the handler's failure.
 */
@Component
public class JpaDeliveryLogStore implements DeliveryLogStore {

    private final DeliveryLogRepository repository;
    private final TransactionOperations ownTransaction;

    @Autowired
    public JpaDeliveryLogStore(DeliveryLogRepository repository, PlatformTransactionManager transactionManager) {
        this(repository, requiresNew(transactionManager));
    }

    /** Without a transaction manager (unit tests): entries are written through the repository directly. */
    public JpaDeliveryLogStore(DeliveryLogRepository repository) {
        this(repository, TransactionOperations.withoutTransaction());
    }

    JpaDeliveryLogStore(DeliveryLogRepository repository, TransactionOperations ownTransaction) {
        this.repository = repository;
        this.ownTransaction = ownTransaction;
    }

    private static TransactionOperations requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @Override
    public boolean alreadyDelivered(UUID kafkaEventId, UUID userId, NotificationChannel channel) {
        return repository.existsByKafkaEventIdAndUserIdAndChannel(kafkaEventId, userId, channel);
    }

    @Override
    public void record(DeliveryLogEntity entry) {
        try {
            // saveAndFlush so a duplicate key surfaces here, inside the try, rather than when the
            // inner transaction commits.
            ownTransaction.executeWithoutResult(status -> repository.saveAndFlush(entry));
        } catch (DataIntegrityViolationException raceLost) {
            // Another instance persisted the same (kafkaEventId, userId, channel) concurrently — the
            // dedup guarantee still holds, so treat this as a no-op.
        }
    }
}
