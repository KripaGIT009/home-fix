package com.homefix.customer.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from the {@code homefix.customer.*} configuration namespace.
 */
@ConfigurationProperties(prefix = "homefix.customer")
public class CustomerProperties {

    /** Maximum number of saved addresses per customer (Requirement 2.3). */
    private int maxAddresses = 10;

    private final Deletion deletion = new Deletion();

    public int getMaxAddresses() {
        return maxAddresses;
    }

    public void setMaxAddresses(int maxAddresses) {
        this.maxAddresses = maxAddresses;
    }

    public Deletion getDeletion() {
        return deletion;
    }

    /**
     * Data-deletion SLAs (Requirement 26.8, 26.9).
     */
    public static class Deletion {

        /** Maximum time to acknowledge a deletion request (Requirement 26.8). */
        private Duration acknowledgeWithin = Duration.ofHours(24);

        /** Maximum time to anonymize PII after acknowledgment (Requirement 26.9). */
        private Duration anonymizeWithin = Duration.ofDays(30);

        public Duration getAcknowledgeWithin() {
            return acknowledgeWithin;
        }

        public void setAcknowledgeWithin(Duration acknowledgeWithin) {
            this.acknowledgeWithin = acknowledgeWithin;
        }

        public Duration getAnonymizeWithin() {
            return anonymizeWithin;
        }

        public void setAnonymizeWithin(Duration anonymizeWithin) {
            this.anonymizeWithin = anonymizeWithin;
        }
    }
}
