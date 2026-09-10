package com.homefix.payment.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Settlement bank-transfer lifecycle status and permitted transitions (Requirement 14.3-14.4).
 *
 * <pre>
 *   PENDING    -&gt; PROCESSING | FAILED
 *   PROCESSING -&gt; COMPLETED | FAILED
 *   COMPLETED  -&gt; (terminal)
 *   FAILED     -&gt; (terminal)
 * </pre>
 */
public enum SettlementStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    private static final Map<SettlementStatus, Set<SettlementStatus>> PERMITTED;

    static {
        Map<SettlementStatus, Set<SettlementStatus>> m = new EnumMap<>(SettlementStatus.class);
        m.put(PENDING, EnumSet.of(PROCESSING, FAILED));
        m.put(PROCESSING, EnumSet.of(COMPLETED, FAILED));
        m.put(COMPLETED, EnumSet.noneOf(SettlementStatus.class));
        m.put(FAILED, EnumSet.noneOf(SettlementStatus.class));
        PERMITTED = Collections.unmodifiableMap(m);
    }

    public boolean canTransitionTo(SettlementStatus target) {
        return target != null && PERMITTED.get(this).contains(target);
    }

    public boolean isTerminal() {
        return PERMITTED.get(this).isEmpty();
    }
}
