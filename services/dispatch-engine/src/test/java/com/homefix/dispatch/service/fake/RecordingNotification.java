package com.homefix.dispatch.service.fake;

import com.homefix.dispatch.port.NotificationPort;

import java.util.UUID;

/** Records the failure notifications fired on the SEARCHING_FAILED path (Requirement 8.9). */
public class RecordingNotification implements NotificationPort {

    private int customerNotifications;
    private int dispatcherAlerts;
    private UUID lastCustomerId;

    @Override
    public void notifyCustomerNoProviderAvailable(UUID bookingId, UUID customerId) {
        customerNotifications++;
        lastCustomerId = customerId;
    }

    @Override
    public void alertDispatcherTeam(UUID bookingId) {
        dispatcherAlerts++;
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
