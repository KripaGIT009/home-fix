package com.homefix.promotion.domain;

/**
 * A coupon's lifecycle state as the Admin Portal shows it (Requirement 19.2, Requirement 21.4).
 * Not stored: derived by {@link Coupon#statusOn} from the {@code active} flag and the expiry date.
 *
 * <ul>
 *   <li>{@link #EXPIRED} — the expiry date has passed. Takes precedence over the flag: an expired
 *       coupon can never be redeemed again, whether or not it was also deactivated.</li>
 *   <li>{@link #INACTIVE} — deactivated by an Admin (21.4) and not yet expired.</li>
 *   <li>{@link #ACTIVE} — active and not expired. A coupon whose window has not opened yet, or
 *       whose total limit is used up, is still ACTIVE: the portal has no state for either, and
 *       checkout reports those constraints on its own (21.2).</li>
 * </ul>
 */
public enum CouponStatus {
    ACTIVE,
    INACTIVE,
    EXPIRED
}
