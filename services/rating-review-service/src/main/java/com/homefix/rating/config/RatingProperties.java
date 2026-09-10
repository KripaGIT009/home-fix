package com.homefix.rating.config;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Tunable rating-and-review limits and thresholds (Requirement 15).
 *
 * <p>Defaults match the acceptance criteria; every value is overridable via configuration so
 * operations can tune limits without a code change.
 */
@ConfigurationProperties(prefix = "homefix.rating")
public class RatingProperties {

    /** Review prompt open window from PAYMENT_COMPLETED (Requirement 15.1, 15.2, Property 15). */
    private Duration reviewWindow = Duration.ofDays(7);

    /** Reviews within this window receive the higher weight in the aggregate (Requirement 15.4). */
    private Duration recentWindow = Duration.ofDays(90);

    /** Weight multiplier for recent reviews (Requirement 15.4, Property 16). */
    private BigDecimal recentWeight = new BigDecimal("1.5");

    /** Weight multiplier for older reviews (Requirement 15.4, Property 16). */
    private BigDecimal olderWeight = new BigDecimal("1.0");

    /** Aggregate below this threshold auto-flags the provider UNDER_REVIEW (Requirement 15.7, 15.8). */
    private BigDecimal underReviewThreshold = new BigDecimal("3.0");

    /** Maximum length of the optional text review (Requirement 15.3). */
    private int maxReviewTextLength = 1000;

    @NestedConfigurationProperty
    private Fraud fraud = new Fraud();

    @NestedConfigurationProperty
    private Attachments attachments = new Attachments();

    @NestedConfigurationProperty
    private Topics topics = new Topics();

    public Duration getReviewWindow() {
        return reviewWindow;
    }

    public void setReviewWindow(Duration reviewWindow) {
        this.reviewWindow = reviewWindow;
    }

    public Duration getRecentWindow() {
        return recentWindow;
    }

    public void setRecentWindow(Duration recentWindow) {
        this.recentWindow = recentWindow;
    }

    public BigDecimal getRecentWeight() {
        return recentWeight;
    }

    public void setRecentWeight(BigDecimal recentWeight) {
        this.recentWeight = recentWeight;
    }

    public BigDecimal getOlderWeight() {
        return olderWeight;
    }

    public void setOlderWeight(BigDecimal olderWeight) {
        this.olderWeight = olderWeight;
    }

    public BigDecimal getUnderReviewThreshold() {
        return underReviewThreshold;
    }

    public void setUnderReviewThreshold(BigDecimal underReviewThreshold) {
        this.underReviewThreshold = underReviewThreshold;
    }

    public int getMaxReviewTextLength() {
        return maxReviewTextLength;
    }

    public void setMaxReviewTextLength(int maxReviewTextLength) {
        this.maxReviewTextLength = maxReviewTextLength;
    }

    public Fraud getFraud() {
        return fraud;
    }

    public void setFraud(Fraud fraud) {
        this.fraud = fraud;
    }

    public Attachments getAttachments() {
        return attachments;
    }

    public void setAttachments(Attachments attachments) {
        this.attachments = attachments;
    }

    public Topics getTopics() {
        return topics;
    }

    public void setTopics(Topics topics) {
        this.topics = topics;
    }

    /** Fraud-detection tuning (Requirement 15.5, Property 17). */
    public static class Fraud {
        /** Same-IP burst: this many or more reviews within the window flags (Requirement 15.5a). */
        private int sameIpThreshold = 2;
        private Duration sameIpWindow = Duration.ofHours(1);
        /** Star rating deviating above this many SD from provider mean flags (Requirement 15.5b). */
        private double deviationSdThreshold = 2.0;
        /** Accounts younger than this at submission flag the review (Requirement 15.5c). */
        private Duration minAccountAge = Duration.ofHours(24);

        public int getSameIpThreshold() {
            return sameIpThreshold;
        }

        public void setSameIpThreshold(int sameIpThreshold) {
            this.sameIpThreshold = sameIpThreshold;
        }

        public Duration getSameIpWindow() {
            return sameIpWindow;
        }

        public void setSameIpWindow(Duration sameIpWindow) {
            this.sameIpWindow = sameIpWindow;
        }

        public double getDeviationSdThreshold() {
            return deviationSdThreshold;
        }

        public void setDeviationSdThreshold(double deviationSdThreshold) {
            this.deviationSdThreshold = deviationSdThreshold;
        }

        public Duration getMinAccountAge() {
            return minAccountAge;
        }

        public void setMinAccountAge(Duration minAccountAge) {
            this.minAccountAge = minAccountAge;
        }
    }

    /** Photo-attachment limits (Requirement 15.3). */
    public static class Attachments {
        private int maxCount = 5;
        private long maxBytes = 10L * 1024 * 1024;

        public int getMaxCount() {
            return maxCount;
        }

        public void setMaxCount(int maxCount) {
            this.maxCount = maxCount;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    /** Kafka topic names this service consumes and produces. */
    public static class Topics {
        private String paymentCompleted = "PaymentCompleted";
        private String reviewSubmitted = "ReviewSubmitted";

        public String getPaymentCompleted() {
            return paymentCompleted;
        }

        public void setPaymentCompleted(String paymentCompleted) {
            this.paymentCompleted = paymentCompleted;
        }

        public String getReviewSubmitted() {
            return reviewSubmitted;
        }

        public void setReviewSubmitted(String reviewSubmitted) {
            this.reviewSubmitted = reviewSubmitted;
        }
    }
}
