package com.homefix.outbox.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Tunable outbox-relay parameters (Requirement 22.4).
 *
 * <p>Defaults match the acceptance criteria; every value is overridable via configuration so
 * operations can tune the poller and retry policy without a code change.
 */
@ConfigurationProperties(prefix = "homefix.outbox-processor")
public class OutboxProcessorProperties {

    /** Maximum number of due PENDING rows claimed and relayed per poll cycle. */
    private int batchSize = 100;

    /** Interval between poll cycles. */
    private Duration pollInterval = Duration.ofSeconds(1);

    /**
     * How long a claim keeps a row out of every other relay instance's claims. Must comfortably
     * exceed {@link #publishTimeout} plus the producer's {@code max.block.ms}: a relay only starts
     * a publish while at least {@code publishTimeout} of its lease remains, and a relay that dies
     * mid-batch leaves its unpublished rows invisible for at most this long.
     */
    private Duration claimLease = Duration.ofMinutes(2);

    /** Maximum wait for the broker ACK of one publish before it counts as a failed attempt. */
    private Duration publishTimeout = Duration.ofSeconds(30);

    @NestedConfigurationProperty
    private Retry retry = new Retry();

    @NestedConfigurationProperty
    private Topics topics = new Topics();

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getClaimLease() {
        return claimLease;
    }

    public void setClaimLease(Duration claimLease) {
        this.claimLease = claimLease;
    }

    public Duration getPublishTimeout() {
        return publishTimeout;
    }

    public void setPublishTimeout(Duration publishTimeout) {
        this.publishTimeout = publishTimeout;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public Topics getTopics() {
        return topics;
    }

    public void setTopics(Topics topics) {
        this.topics = topics;
    }

    /**
     * Exponential-backoff retry policy for publish failures (Requirement 22.4). The delay is
     * persisted on the row as its next attempt time, never slept in-line.
     */
    public static class Retry {
        /** Delay before the first retry; doubled each subsequent attempt. */
        private Duration initialInterval = Duration.ofSeconds(1);

        /** Ceiling the retry delay may never exceed. */
        private Duration maxInterval = Duration.ofSeconds(60);

        /** Maximum number of publish attempts before the row is marked FAILED and ops alerted. */
        private int maxAttempts = 10;

        public Duration getInitialInterval() {
            return initialInterval;
        }

        public void setInitialInterval(Duration initialInterval) {
            this.initialInterval = initialInterval;
        }

        public Duration getMaxInterval() {
            return maxInterval;
        }

        public void setMaxInterval(Duration maxInterval) {
            this.maxInterval = maxInterval;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }

    /**
     * Kafka topic routing. Each outbox row carries an {@code eventType}; the relay resolves the
     * destination topic by looking the event type up in {@link #mapping}, falling back to
     * {@link #defaultTopic} when no explicit mapping exists.
     */
    public static class Topics {
        /** Explicit eventType -&gt; topic overrides. */
        private Map<String, String> mapping = new HashMap<>();

        /** Topic used when an event type has no explicit mapping. */
        private String defaultTopic = "domain-events";

        public Map<String, String> getMapping() {
            return mapping;
        }

        public void setMapping(Map<String, String> mapping) {
            this.mapping = mapping;
        }

        public String getDefaultTopic() {
            return defaultTopic;
        }

        public void setDefaultTopic(String defaultTopic) {
            this.defaultTopic = defaultTopic;
        }
    }
}
