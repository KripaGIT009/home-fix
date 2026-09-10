package com.homefix.admin.dispatch;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * Holds the current dispatch matching weights. Because a {@link DispatchWeights} value can only
 * be constructed when valid, {@link #update} either swaps in an already-validated set or is never
 * called (the controller validates first), so a rejected update never mutates the stored weights
 * (Requirement 19.5).
 */
@Component
public class DispatchWeightsStore {

    private final AtomicReference<DispatchWeights> current =
            new AtomicReference<>(DispatchWeights.DEFAULT);

    public DispatchWeights current() {
        return current.get();
    }

    public DispatchWeights update(DispatchWeights validated) {
        current.set(validated);
        return validated;
    }
}
