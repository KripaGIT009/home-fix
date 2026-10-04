package com.homefix.payment.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Payment transaction lifecycle states and the single source of truth for permitted transitions
 * (Requirement 12.4, Property 12).
 *
 * <pre>
 *   PENDING            -&gt; SUCCESS | FAILED
 *   SUCCESS            -&gt; REFUNDED | PARTIALLY_REFUNDED
 *   PARTIALLY_REFUNDED -&gt; PARTIALLY_REFUNDED | REFUNDED
 *   FAILED, REFUNDED   -&gt; (terminal)
 * </pre>
 *
 * <p>PARTIALLY_REFUNDED is not terminal: a payment refunded in part can be refunded again, in part
 * (it stays PARTIALLY_REFUNDED) or for the remainder (it becomes REFUNDED), until the refunded total
 * reaches the captured amount. {@code PaymentTransaction#checkRefundable} enforces that ceiling; the
 * refund flow already accepted PARTIALLY_REFUNDED, but the state machine used to refuse every exit
 * from it, so a second refund was impossible.
 *
 * <p>A transition is permitted <em>if and only if</em> the target state appears in the permitted
 * set for the current state; every other transition is rejected.
 */
public enum TransactionStatus {
    PENDING,
    SUCCESS,
    FAILED,
    REFUNDED,
    PARTIALLY_REFUNDED;

    private static final Map<TransactionStatus, Set<TransactionStatus>> PERMITTED;

    static {
        Map<TransactionStatus, Set<TransactionStatus>> m = new EnumMap<>(TransactionStatus.class);
        m.put(PENDING, EnumSet.of(SUCCESS, FAILED));
        m.put(SUCCESS, EnumSet.of(REFUNDED, PARTIALLY_REFUNDED));
        m.put(FAILED, EnumSet.noneOf(TransactionStatus.class));
        m.put(REFUNDED, EnumSet.noneOf(TransactionStatus.class));
        m.put(PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED));
        PERMITTED = Collections.unmodifiableMap(m);
    }

    /**
     * @return {@code true} if a transition from this state to {@code target} is permitted by the
     *         defined state machine.
     */
    public boolean canTransitionTo(TransactionStatus target) {
        return target != null && PERMITTED.get(this).contains(target);
    }

    /** @return the set of states this state may transition to (never {@code null}). */
    public Set<TransactionStatus> permittedTargets() {
        return PERMITTED.get(this);
    }

    /** @return {@code true} if this state has no outgoing transitions. */
    public boolean isTerminal() {
        return PERMITTED.get(this).isEmpty();
    }
}
