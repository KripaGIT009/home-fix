package com.homefix.chat.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Tunable chat-domain limits, retention window, and Kafka topic names (Requirement 18).
 *
 * <p>Defaults match the acceptance criteria; all values are overridable via configuration so
 * operations can tune limits without a code change.
 */
@ConfigurationProperties(prefix = "homefix.chat")
public class ChatProperties {

    /**
     * Minimum time a message must be retained, measured from the booking's creation date and
     * independent of channel status (Requirement 18.4). Defaults to 90 days.
     */
    private Duration retention = Duration.ofDays(90);

    /**
     * Maximum time a recipient's WebSocket session may be idle before the recipient is treated as
     * offline and eligible for a push notification (Requirement 18.3).
     */
    private Duration offlinePushWindow = Duration.ofSeconds(10);

    /** Push vendor adapter selector: {@code log} (dev/test) or a concrete Notification adapter. */
    private String pushProvider = "log";

    @NestedConfigurationProperty
    private Topics topics = new Topics();

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public Duration getOfflinePushWindow() {
        return offlinePushWindow;
    }

    public void setOfflinePushWindow(Duration offlinePushWindow) {
        this.offlinePushWindow = offlinePushWindow;
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

    /** Kafka topic names driving the channel lifecycle (Requirement 18.1, 18.5). */
    public static class Topics {
        /** Activates a channel on transition to PROVIDER_ACCEPTED (Requirement 18.1). */
        private String providerAccepted = "ProviderAccepted";
        /** Deactivates a channel on transition to PAYMENT_COMPLETED (Requirement 18.5). */
        private String paymentCompleted = "PaymentCompleted";
        /** Deactivates a channel on transition to CANCELLED (Requirement 18.5). */
        private String bookingCancelled = "BookingCancelled";

        public String getProviderAccepted() {
            return providerAccepted;
        }

        public void setProviderAccepted(String providerAccepted) {
            this.providerAccepted = providerAccepted;
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
    }
}
