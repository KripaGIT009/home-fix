package com.homefix.dispatch.service.fake;

import com.homefix.dispatch.port.NotificationPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Records the dispatcher alerts fired on the SEARCHING_FAILED path (Requirement 8.9) and the
 * job-offer pushes sent to providers (Requirement 8.5).
 */
public class RecordingNotification implements NotificationPort {

    private int dispatcherAlerts;
    private final List<UUID> offerPushes = new ArrayList<>();

    @Override
    public void alertDispatcherTeam(UUID bookingId) {
        dispatcherAlerts++;
    }

    @Override
    public synchronized void notifyProviderOfJobOffer(UUID bookingId, UUID providerId, Instant expiresAt) {
        offerPushes.add(providerId);
    }

    /** Providers pushed about an offer, in order. */
    public synchronized List<UUID> offerPushes() {
        return List.copyOf(offerPushes);
    }

    public int dispatcherAlerts() {
        return dispatcherAlerts;
    }
}
