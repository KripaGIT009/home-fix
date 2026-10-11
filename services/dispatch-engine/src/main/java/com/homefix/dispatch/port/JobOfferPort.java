package com.homefix.dispatch.port;

import com.homefix.dispatch.domain.DispatchRequest;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound port that offers a job to a wave of providers at once and awaits the first acceptance
 * (Requirements 8.5-8.7). Sending the offers and blocking for the answers is a transport concern
 * (push notification + provider-app callback, or a synchronous fake in tests), so it is hidden
 * behind this port.
 */
public interface JobOfferPort {

    /**
     * Offers {@code request}'s booking to every provider in {@code providerIds} at the same time
     * and waits up to {@code timeout}. The first provider to accept takes the job and the other
     * offers are withdrawn; the wait ends early once someone accepts or every provider has
     * answered.
     *
     * @return who accepted (if anyone) and how each provider in the wave answered
     */
    WaveOutcome offer(DispatchRequest request, List<UUID> providerIds, Duration timeout);

    /** The outcome of a single offer within a wave. */
    enum OfferOutcome {
        ACCEPTED,
        REJECTED,
        TIMED_OUT
    }

    /**
     * Result of one wave.
     *
     * @param acceptedBy the provider who took the job, or {@code null} when nobody accepted
     * @param outcomes   each offered provider's answer; providers withdrawn because someone else
     *                   accepted first read as {@link OfferOutcome#TIMED_OUT}
     */
    record WaveOutcome(UUID acceptedBy, Map<UUID, OfferOutcome> outcomes) {

        public WaveOutcome {
            outcomes = Map.copyOf(outcomes);
        }

        /** Nobody answered: every provider in the wave timed out. */
        public static WaveOutcome noAnswer(List<UUID> providerIds) {
            Map<UUID, OfferOutcome> all = new LinkedHashMap<>();
            providerIds.forEach(p -> all.put(p, OfferOutcome.TIMED_OUT));
            return new WaveOutcome(null, all);
        }

        /** The provider's answer, TIMED_OUT when they were not in the wave's results. */
        public OfferOutcome outcomeOf(UUID providerId) {
            return outcomes.getOrDefault(providerId, OfferOutcome.TIMED_OUT);
        }
    }
}
