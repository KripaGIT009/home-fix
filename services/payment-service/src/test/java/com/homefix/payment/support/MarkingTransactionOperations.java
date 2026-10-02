package com.homefix.payment.support;

import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Test {@link TransactionOperations} that marks the calling thread as inside an actual transaction
 * for the duration of each callback, exactly as a real {@code TransactionTemplate} does, but without
 * a database. This lets unit tests assert <em>where</em> transaction boundaries fall: code under test
 * that checks {@link TransactionSynchronizationManager#isActualTransactionActive()} (for example the
 * {@code Retries} guard, or test doubles for gateways and the MANDATORY outbox publisher) sees the
 * same answer it would in production.
 *
 * <p>Nested calls join the outer "transaction" (REQUIRED semantics). {@link #executions()} counts
 * outermost transactions so tests can assert how many were opened.
 */
public class MarkingTransactionOperations implements TransactionOperations {

    private int executions;

    @Override
    public <T> T execute(TransactionCallback<T> action) throws TransactionException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return action.doInTransaction(new SimpleTransactionStatus(false));
        }
        executions++;
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            return action.doInTransaction(new SimpleTransactionStatus(true));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /** @return the number of outermost transactions opened so far. */
    public int executions() {
        return executions;
    }

    /** @return whether the calling thread is currently inside a transaction opened by this class. */
    public static boolean inTransaction() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }
}
