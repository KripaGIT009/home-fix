package com.homefix.promotion.domain;

/**
 * Pure logic for the paired counter mutations that back atomic redemption (Requirement 21.3,
 * Property 20) and cancellation decrement (Requirement 21.5, Property 21). Factored out of the
 * service and entities so the "never exceed limits" and "decrement restores pre-redemption values"
 * invariants can be reasoned about and property-tested independently of JPA, Redis, or Spring.
 *
 * <p>A redemption is only ever applied when <em>both</em> the total and per-user counters have
 * headroom, so the two counters always move together — never one without the other.
 */
public final class CouponCounters {

    private CouponCounters() {
    }

    /**
     * Decides whether a single redemption may be applied given the current counter values and the
     * configured limits. Both counters must have headroom (Requirement 21.3, Property 20).
     *
     * @return {@code true} if incrementing both counters by one keeps each within its limit.
     */
    public static boolean canRedeem(int totalUsed, int totalLimit,
                                    int userUsage, int perUserLimit) {
        return totalUsed < totalLimit && userUsage < perUserLimit;
    }

    /**
     * @return the total-usage value after a redemption, clamped so it never exceeds the limit.
     */
    public static int incremented(int used, int limit) {
        return used < limit ? used + 1 : used;
    }

    /**
     * @return the usage value after a cancellation decrement, clamped so it never goes below zero
     *         (Requirement 21.5, Property 21).
     */
    public static int decremented(int used) {
        return used > 0 ? used - 1 : 0;
    }
}
