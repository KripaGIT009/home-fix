package com.homefix.notification.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Tunable notification-domain limits and vendor selectors (Requirement 17).
 *
 * <p>Defaults match the acceptance criteria; all values are overridable via configuration so
 * operations can tune limits and swap vendors without a code change.
 */
@ConfigurationProperties(prefix = "homefix.notification")
public class NotificationProperties {

    /** Maximum delivery attempts per (event, channel) before permanent failure (Requirement 17.8). */
    private int maxAttempts = 3;

    /**
     * Backoff before the first retry; each subsequent retry doubles it, yielding the
     * 1 s -> 2 s -> 4 s schedule mandated by Requirement 17.8.
     */
    private Duration retryInitialBackoff = Duration.ofSeconds(1);

    /** SMS vendor adapter selector: {@code log} (dev/test), {@code twilio}, {@code vonage}. */
    private String smsProvider = "log";

    /** Email vendor adapter selector: {@code log} (dev/test), {@code ses}, {@code sendgrid}. */
    private String emailProvider = "log";

    /** Push vendor adapter selector: {@code log} (dev/test), {@code fcm}. */
    private String pushProvider = "log";

    @NestedConfigurationProperty
    private Topics topics = new Topics();

    @NestedConfigurationProperty
    private Clients clients = new Clients();

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getRetryInitialBackoff() {
        return retryInitialBackoff;
    }

    public void setRetryInitialBackoff(Duration retryInitialBackoff) {
        this.retryInitialBackoff = retryInitialBackoff;
    }

    public String getSmsProvider() {
        return smsProvider;
    }

    public void setSmsProvider(String smsProvider) {
        this.smsProvider = smsProvider;
    }

    public String getEmailProvider() {
        return emailProvider;
    }

    public void setEmailProvider(String emailProvider) {
        this.emailProvider = emailProvider;
    }

    public String getPushProvider() {
        return pushProvider;
    }

    public void setPushProvider(String pushProvider) {
        this.pushProvider = pushProvider;
    }

    public Topics getTopics() {
        return topics;
    }

    public void setTopics(Topics topics) {
        this.topics = topics;
    }

    public Clients getClients() {
        return clients;
    }

    public void setClients(Clients clients) {
        this.clients = clients;
    }

    /** Kafka topic names this service consumes (one per lifecycle event, Requirement 17.5). */
    public static class Topics {
        private String bookingCreated = "BookingCreated";
        private String providerAssigned = "ProviderAssigned";
        private String providerAccepted = "ProviderAccepted";
        private String providerRejected = "ProviderRejected";
        private String providerArriving = "ProviderArriving";
        private String providerArrived = "ProviderArrived";
        private String jobStarted = "JobStarted";
        private String jobCompleted = "JobCompleted";
        private String paymentCompleted = "PaymentCompleted";
        private String bookingCancelled = "BookingCancelled";
        private String reviewSubmitted = "ReviewSubmitted";

        public String getBookingCreated() {
            return bookingCreated;
        }

        public void setBookingCreated(String bookingCreated) {
            this.bookingCreated = bookingCreated;
        }

        public String getProviderAssigned() {
            return providerAssigned;
        }

        public void setProviderAssigned(String providerAssigned) {
            this.providerAssigned = providerAssigned;
        }

        public String getProviderAccepted() {
            return providerAccepted;
        }

        public void setProviderAccepted(String providerAccepted) {
            this.providerAccepted = providerAccepted;
        }

        public String getProviderRejected() {
            return providerRejected;
        }

        public void setProviderRejected(String providerRejected) {
            this.providerRejected = providerRejected;
        }

        public String getProviderArriving() {
            return providerArriving;
        }

        public void setProviderArriving(String providerArriving) {
            this.providerArriving = providerArriving;
        }

        public String getProviderArrived() {
            return providerArrived;
        }

        public void setProviderArrived(String providerArrived) {
            this.providerArrived = providerArrived;
        }

        public String getJobStarted() {
            return jobStarted;
        }

        public void setJobStarted(String jobStarted) {
            this.jobStarted = jobStarted;
        }

        public String getJobCompleted() {
            return jobCompleted;
        }

        public void setJobCompleted(String jobCompleted) {
            this.jobCompleted = jobCompleted;
        }

        public String getPaymentCompleted() {
            return paymentCompleted;
        }

        public void setPaymentCompleted(String paymentCompleted) {
            this.paymentCompleted = paymentCompleted;
        }

        public String getBookingCancelled() {
            return bookingCancelled;
        }

        public void setBookingCancelled(String bookingCancelled) {
            this.bookingCancelled = bookingCancelled;
        }

        public String getReviewSubmitted() {
            return reviewSubmitted;
        }

        public void setReviewSubmitted(String reviewSubmitted) {
            this.reviewSubmitted = reviewSubmitted;
        }
    }

    /** Downstream service base URLs. */
    public static class Clients {
        private String preferenceServiceBaseUrl = "http://customer-service";

        public String getPreferenceServiceBaseUrl() {
            return preferenceServiceBaseUrl;
        }

        public void setPreferenceServiceBaseUrl(String preferenceServiceBaseUrl) {
            this.preferenceServiceBaseUrl = preferenceServiceBaseUrl;
        }
    }
}
