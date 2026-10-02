package com.homefix.complaint.support;

import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Test {@link TransactionOperations} that marks the calling thread as inside an actual transaction
 * for the duration of each callback, exactly as a real {@code TransactionTemplate} does, but without
 * a database. Lets unit tests assert <em>where</em> transaction boundaries fall: a test double that
 * checks {@link TransactionSynchronizationManager#isActualTransactionActive()} (for example the
 * refund port) sees the same answer it would in production. Same helper as the Payment Service's.
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
