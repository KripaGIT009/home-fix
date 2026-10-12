package com.homefix.dispatch.service.fake;

import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.port.JobOfferPort;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Fake {@link JobOfferPort} whose response per provider is scripted. Defaults to TIMED_OUT for any
 * provider not explicitly configured, so "nobody accepts" is the easy default. Within a wave the
 * first provider (in wave order) scripted to accept takes the job; everyone else in that wave reads
 * as withdrawn (TIMED_OUT) unless they were scripted to decline. Optionally asserts that every
 * provider's exclusive lock is held at the moment the wave is offered.
 */
public class ScriptedJobOffer implements JobOfferPort {

    private final Map<UUID, OfferOutcome> outcomes = new HashMap<>();
    private final List<UUID> offeredProviders = new ArrayList<>();
    private final List<List<UUID>> waves = new ArrayList<>();
    private final List<Duration> windows = new ArrayList<>();
    private final InMemoryLock lockToVerify;
    private boolean lockHeldAtEveryOffer = true;
    private BiConsumer<UUID, UUID> onOffer = (booking, provider) -> { };
    private Consumer<Duration> whenNobodyAccepts = window -> { };

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

    /** Runs {@code hook(bookingId, providerId)} for each provider while their offer is outstanding. */
    public ScriptedJobOffer onOffer(BiConsumer<UUID, UUID> hook) {
        this.onOffer = hook;
        return this;
    }

    /** Runs {@code hook(window)} when nobody in a wave accepts, e.g. to let the clock run out the window. */
    public ScriptedJobOffer whenNobodyAccepts(Consumer<Duration> hook) {
        this.whenNobodyAccepts = hook;
        return this;
    }

    @Override
    public WaveOutcome offer(DispatchRequest request, List<UUID> providerIds, Duration timeout) {
        waves.add(List.copyOf(providerIds));
        windows.add(timeout);
        Map<UUID, OfferOutcome> result = new LinkedHashMap<>();
        UUID acceptedBy = null;
        for (UUID providerId : providerIds) {
            offeredProviders.add(providerId);
            onOffer.accept(request.bookingId(), providerId);
            if (lockToVerify != null && !lockToVerify.isHeld(providerId)) {
                lockHeldAtEveryOffer = false;
            }
            OfferOutcome scripted = outcomes.getOrDefault(providerId, OfferOutcome.TIMED_OUT);
            if (scripted == OfferOutcome.ACCEPTED && acceptedBy == null) {
                acceptedBy = providerId;
                result.put(providerId, OfferOutcome.ACCEPTED);
            } else if (scripted == OfferOutcome.ACCEPTED) {
                // Lost the race to an earlier provider in the same wave: their offer was withdrawn.
                result.put(providerId, OfferOutcome.TIMED_OUT);
            } else {
                result.put(providerId, scripted);
            }
        }
        if (acceptedBy == null) {
            whenNobodyAccepts.accept(timeout);
        }
        return new WaveOutcome(acceptedBy, result);
    }

    /** Providers offered, in the order they were offered. */
    public List<UUID> offeredProviders() {
        return offeredProviders;
    }

    /** Each wave's providers, in the order the waves were offered. */
    public List<List<UUID>> waves() {
        return waves;
    }

    /** Each wave's response window, in order. */
    public List<Duration> windows() {
        return windows;
    }

    /** True iff every provider's lock was held each time they were offered (Requirement 8.11). */
    public boolean lockHeldAtEveryOffer() {
        return lockHeldAtEveryOffer;
    }
}
