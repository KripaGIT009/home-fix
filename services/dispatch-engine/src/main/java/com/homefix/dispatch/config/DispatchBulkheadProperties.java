package com.homefix.dispatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sizing for the two bulkhead pools (Requirement 24.5). Emergency and scheduled pools are sized
 * independently so operations can guarantee headroom for emergencies.
 */
@ConfigurationProperties(prefix = "homefix.dispatch.bulkhead")
public class DispatchBulkheadProperties {

    private int emergencyCoreThreads = 8;
    private int emergencyMaxThreads = 16;
    private int emergencyQueueCapacity = 100;

    private int scheduledCoreThreads = 8;
    private int scheduledMaxThreads = 16;
    private int scheduledQueueCapacity = 500;

    public int getEmergencyCoreThreads() {
        return emergencyCoreThreads;
    }

    public void setEmergencyCoreThreads(int emergencyCoreThreads) {
        this.emergencyCoreThreads = emergencyCoreThreads;
    }

    public int getEmergencyMaxThreads() {
        return emergencyMaxThreads;
    }

    public void setEmergencyMaxThreads(int emergencyMaxThreads) {
        this.emergencyMaxThreads = emergencyMaxThreads;
    }

    public int getEmergencyQueueCapacity() {
        return emergencyQueueCapacity;
    }

    public void setEmergencyQueueCapacity(int emergencyQueueCapacity) {
        this.emergencyQueueCapacity = emergencyQueueCapacity;
    }

    public int getScheduledCoreThreads() {
        return scheduledCoreThreads;
    }

    public void setScheduledCoreThreads(int scheduledCoreThreads) {
        this.scheduledCoreThreads = scheduledCoreThreads;
    }

    public int getScheduledMaxThreads() {
        return scheduledMaxThreads;
    }

    public void setScheduledMaxThreads(int scheduledMaxThreads) {
        this.scheduledMaxThreads = scheduledMaxThreads;
    }

    public int getScheduledQueueCapacity() {
        return scheduledQueueCapacity;
    }

    public void setScheduledQueueCapacity(int scheduledQueueCapacity) {
        this.scheduledQueueCapacity = scheduledQueueCapacity;
    }
}
