package com.homefix.dispatch.service.fake;

import com.homefix.dispatch.port.NotificationPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Records the failure notifications fired on the SEARCHING_FAILED path (Requirement 8.9) and the
 * job-offer pushes sent to providers (Requirement 8.5).
 */
public class RecordingNotification implements NotificationPort {

    private int customerNotifications;
    private int dispatcherAlerts;
    private UUID lastCustomerId;
    private final List<UUID> offerPushes = new ArrayList<>();

    @Override
    public void notifyCustomerNoProviderAvailable(UUID bookingId, UUID customerId) {
        customerNotifications++;
        lastCustomerId = customerId;
    }

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

    public int customerNotifications() {
        return customerNotifications;
    }

    public int dispatcherAlerts() {
        return dispatcherAlerts;
    }

    public UUID lastCustomerId() {
        return lastCustomerId;
    }
}
