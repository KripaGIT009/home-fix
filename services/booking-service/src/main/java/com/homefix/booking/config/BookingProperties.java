package com.homefix.booking.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised booking configuration (bound from {@code homefix.booking.*}).
 */
@ConfigurationProperties(prefix = "homefix.booking")
public class BookingProperties {

    /** Minimum lead time for a scheduled booking (Requirement 7.7). */
    private Duration minLeadTime = Duration.ofHours(2);

    /** Maximum scheduling horizon for a scheduled booking (Requirement 7.8). */
    private Duration maxHorizon = Duration.ofDays(90);

    /** Emergency create SLA (Requirement 8.1). */
    private Duration emergencyCreateWithin = Duration.ofSeconds(2);

    /** Emergency SEARCHING_PROVIDER SLA after creation (Requirement 8.1). */
    private Duration emergencySearchingWithin = Duration.ofSeconds(3);

    /** Additional-quote customer response timeout (Requirement 9.9). */
    private Duration additionalQuoteTimeout = Duration.ofMinutes(60);

    /**
     * Default cancellation fee applied when a Service_Subcategory has none configured. Must
     * be within the 0.00-999.99 range (Requirement 9.18).
     */
    private BigDecimal defaultCancellationFee = new BigDecimal("0.00");

    /**
     * How long a booking may wait in AWAITING_ASSIGNMENT, measured from when it first entered the
     * queue, before the sweeper fails it (Requirement MT-7.1, MT-7.2).
     */
    private Duration tenantAssignmentTimeout = Duration.ofMinutes(60);

    /**
     * How long a booking may stay in SEARCHING_PROVIDER before the stalled-search sweeper settles it
     * as if dispatch had found nobody (review 17.5 item 4). It must exceed the longest search the
     * Dispatch Engine can legitimately run (offer timeout × candidates × radius cycles); the sweeper
     * only rescues searches that were lost, such as one interrupted by a restart.
     */
    private Duration providerSearchTimeout = Duration.ofMinutes(60);

    private final Media media = new Media();

    public Duration getMinLeadTime() {
        return minLeadTime;
    }

    public void setMinLeadTime(Duration minLeadTime) {
        this.minLeadTime = minLeadTime;
    }

    public Duration getMaxHorizon() {
        return maxHorizon;
    }

    public void setMaxHorizon(Duration maxHorizon) {
        this.maxHorizon = maxHorizon;
    }

    public Duration getEmergencyCreateWithin() {
        return emergencyCreateWithin;
    }

    public void setEmergencyCreateWithin(Duration emergencyCreateWithin) {
        this.emergencyCreateWithin = emergencyCreateWithin;
    }

    public Duration getEmergencySearchingWithin() {
        return emergencySearchingWithin;
    }

    public void setEmergencySearchingWithin(Duration emergencySearchingWithin) {
        this.emergencySearchingWithin = emergencySearchingWithin;
    }

    public Duration getAdditionalQuoteTimeout() {
        return additionalQuoteTimeout;
    }

    public void setAdditionalQuoteTimeout(Duration additionalQuoteTimeout) {
        this.additionalQuoteTimeout = additionalQuoteTimeout;
    }

    public BigDecimal getDefaultCancellationFee() {
        return defaultCancellationFee;
    }

    public void setDefaultCancellationFee(BigDecimal defaultCancellationFee) {
        this.defaultCancellationFee = defaultCancellationFee;
    }

    public Duration getTenantAssignmentTimeout() {
        return tenantAssignmentTimeout;
    }

    public void setTenantAssignmentTimeout(Duration tenantAssignmentTimeout) {
        this.tenantAssignmentTimeout = tenantAssignmentTimeout;
    }

    public Duration getProviderSearchTimeout() {
        return providerSearchTimeout;
    }

    public void setProviderSearchTimeout(Duration providerSearchTimeout) {
        this.providerSearchTimeout = providerSearchTimeout;
    }

    public Media getMedia() {
        return media;
    }

    /** Media upload constraints (Requirement 7.2). */
    public static class Media {
        private int maxFiles = 10;
        private long maxFileSize = 52_428_800L; // 50 MB
        private List<String> allowedContentTypes = List.of(
                "image/jpeg", "image/png", "video/mp4", "video/quicktime");

        public int getMaxFiles() {
            return maxFiles;
        }

        public void setMaxFiles(int maxFiles) {
            this.maxFiles = maxFiles;
        }

        public long getMaxFileSize() {
            return maxFileSize;
        }

        public void setMaxFileSize(long maxFileSize) {
            this.maxFileSize = maxFileSize;
        }

        public List<String> getAllowedContentTypes() {
            return allowedContentTypes;
        }

        public void setAllowedContentTypes(List<String> allowedContentTypes) {
            this.allowedContentTypes = allowedContentTypes;
        }
    }
}
