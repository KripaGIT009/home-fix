package com.homefix.dispatch.service.fake;

import com.homefix.dispatch.port.JobOfferPort;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Fake {@link JobOfferPort} whose response per provider is scripted. Defaults to TIMED_OUT for any
 * provider not explicitly configured, so "nobody accepts" is the easy default. Optionally asserts
 * that the provider's exclusive lock is held at the moment the offer is made.
 */
public class ScriptedJobOffer implements JobOfferPort {

    private final Map<UUID, OfferOutcome> outcomes = new HashMap<>();
    private final List<UUID> offeredProviders = new ArrayList<>();
    private final InMemoryLock lockToVerify;
    private boolean lockHeldAtEveryOffer = true;
    private BiConsumer<UUID, UUID> onOffer = (booking, provider) -> { };

    public ScriptedJobOffer() {
        this(null);
    }

    /** @param lockToVerify if non-null, each offer asserts the provider is locked at offer time. */
    public ScriptedJobOffer(InMemoryLock lockToVerify) {
        this.lockToVerify = lockToVerify;
    }

    public ScriptedJobOffer respond(UUID providerId, OfferOutcome outcome) {
        outcomes.put(providerId, outcome);
        return this;
    }

    /** Runs {@code hook(bookingId, providerId)} while each offer is outstanding, e.g. to cancel it. */
    public ScriptedJobOffer onOffer(BiConsumer<UUID, UUID> hook) {
        this.onOffer = hook;
        return this;
    }

    @Override
    public OfferOutcome offer(UUID bookingId, UUID providerId, Duration timeout) {
        offeredProviders.add(providerId);
        onOffer.accept(bookingId, providerId);
        if (lockToVerify != null && !lockToVerify.isHeld(providerId)) {
            lockHeldAtEveryOffer = false;
        }
        return outcomes.getOrDefault(providerId, OfferOutcome.TIMED_OUT);
    }

    /** Providers offered, in the order they were offered. */
    public List<UUID> offeredProviders() {
        return offeredProviders;
    }

    /** True iff the provider's lock was held every time an offer was sent (Requirement 8.11). */
    public boolean lockHeldAtEveryOffer() {
        return lockHeldAtEveryOffer;
    }
}
