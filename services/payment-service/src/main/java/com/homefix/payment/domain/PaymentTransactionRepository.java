package com.homefix.payment.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/**
 * Persistence for {@link PaymentTransaction}. The unique {@code idempotency_key} column plus the
 * {@link #findByIdempotencyKey(String)} lookup back the idempotency guarantee (Requirement 12.3,
 * Property 11).
 */
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentTransaction> findByGatewayReference(String gatewayReference);

    /**
     * Loads a transaction holding a row lock ({@code SELECT ... FOR UPDATE}) until the surrounding
     * database transaction ends. The refund flow uses it to serialise refund reservations for one
     * transaction, so two concurrent refunds cannot both pass the amount check (Requirement 12.7).
     * The lock is held only for the short reservation step, never across the gateway call.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentTransaction t where t.id = :id")
    Optional<PaymentTransaction> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Payments whose provider wallet credit has been owed since at or before {@code cutoff}, oldest
     * first. Drives {@code WalletCreditSweeper} (Requirement 12.10, 12.11).
     */
    /**
     * Transactions whose id, booking id or gateway reference contains {@code pattern}, newest
     * first — the query behind the Admin Portal's payment list (Requirement 19.2). The caller
     * passes a lower-case LIKE pattern with {@code !} as the escape character ({@code "%"} lists
     * everything); the ids are compared in their canonical lower-case text form.
     *
     * <p>The id breaks ties so the same search returns the same rows in the same order. The result
     * is a {@code List} bounded by {@code page}: the portal shows one capped list, so no count
     * query is run.
     */
    @Query("select t from PaymentTransaction t"
            + " where lower(cast(t.id as String)) like :pattern escape '!'"
            + " or lower(cast(t.bookingId as String)) like :pattern escape '!'"
            + " or lower(t.gatewayReference) like :pattern escape '!'"
            + " order by t.createdAt desc, t.id desc")
    List<PaymentTransaction> searchForAdmin(@Param("pattern") String pattern, Pageable page);

    @Query("select t from PaymentTransaction t where t.walletCreditPendingSince <= :cutoff"
            + " order by t.walletCreditPendingSince")
    List<PaymentTransaction> findWalletCreditsDue(@Param("cutoff") Instant cutoff, Pageable page);

    /**
     * Clears the wallet-credit marker once the wallet accepted the credit. A JPQL bulk update on
     * purpose: it does not increment {@code version}, so it can never make a concurrent writer of the
     * same row (the charge flow recording its gateway reference, or a refund recording its outcome
     * after money moved at the gateway) fail with an optimistic-lock conflict. Must run inside a
     * transaction.
     *
     * @return the number of rows updated (0 or 1).
     */
    @Modifying
    @Query("update PaymentTransaction t set t.walletCreditPendingSince = null where t.id = :id")
    int clearWalletCreditPending(@Param("id") UUID id);

    /**
     * Records that the wallet permanently refused this payment's credit: clears the owed marker so
     * the sweeper stops re-sending it, and keeps the reason. A bulk update for the same reason as
     * {@link #clearWalletCreditPending(UUID)}. Must run inside a transaction.
     *
     * @return the number of rows updated (0 or 1).
     */
    @Modifying
    @Query("update PaymentTransaction t set t.walletCreditPendingSince = null,"
            + " t.walletCreditFailure = :reason where t.id = :id")
    int markWalletCreditFailed(@Param("id") UUID id, @Param("reason") String reason);
}
