package com.homefix.notification.delivery;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.homefix.notification.channel.NotificationDeliveryException;
import com.homefix.notification.config.NotificationProperties;
import com.homefix.notification.domain.DeliveryStatus;
import com.homefix.notification.domain.EventTemplateResolver;
import com.homefix.notification.domain.NotificationChannel;
import com.homefix.notification.domain.NotificationEvent;
import com.homefix.notification.domain.NotificationPreferences;
import com.homefix.notification.domain.RenderedMessage;
import com.homefix.notification.domain.RetrySchedule;
import com.homefix.notification.preference.NotificationPreferencePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Orchestrates multi-channel notification delivery for one event addressed to one recipient
 * (Requirement 17).
 *
 * <p>For each addressed event the service:
 * <ol>
 *   <li>Resolves the candidate channels and channel-safe content for the recipient's audience
 *       from the {@link EventTemplateResolver} (Requirement 17.5).</li>
 *   <li>Loads the user's preferences, defaulting to all channels enabled when preference data is
 *       unavailable (Requirement 17.6).</li>
 *   <li>For each candidate channel that is not already delivered
 *       ({@code (kafkaEventId, recipient, channel)} dedup), is enabled, and that the recipient
 *       has an address for, attempts delivery with up to {@code maxAttempts} tries and
 *       1 s / 2 s / 4 s exponential backoff, then records the outcome in the delivery log
 *       (Requirements 17.7, 17.8, Property 22).</li>
 * </ol>
 *
 * <p>A channel disabled by preference is skipped and recorded as {@code SKIPPED_PREFERENCE}; a
 * channel the recipient has no address on (no phone, email or push token on record) is skipped
 * and recorded as {@code SKIPPED_NO_CONTACT} without burning retries that cannot succeed; a
 * channel already present in the delivery log is silently discarded with no dispatch. No PII is
 * logged (Requirement 26.4).
 */
@Service
public class NotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);

    private final EventTemplateResolver templateResolver;
    private final NotificationPreferencePort preferencePort;
    private final DeliveryLogStore deliveryLogStore;
    private final ChannelDispatcher channelDispatcher;
    private final RetrySchedule retrySchedule;
    private final Clock clock;
    private final Sleeper sleeper;

    // These classes keep a second, package-private constructor so tests can pin the Clock.
    // With more than one constructor Spring will not guess: without @Autowired it falls back
    // to a no-arg constructor that does not exist and the bean fails to instantiate.
    @Autowired
    public NotificationDeliveryService(EventTemplateResolver templateResolver,
                                       NotificationPreferencePort preferencePort,
                                       DeliveryLogStore deliveryLogStore,
                                       ChannelDispatcher channelDispatcher,
                                       NotificationProperties properties) {
        this(templateResolver, preferencePort, deliveryLogStore, channelDispatcher,
                new RetrySchedule(properties.getMaxAttempts(), properties.getRetryInitialBackoff()),
                Clock.systemUTC(), Thread::sleep);
    }

    // Visible for testing so unit tests avoid real backoff sleeps and can pin the clock/schedule.
    NotificationDeliveryService(EventTemplateResolver templateResolver,
                                NotificationPreferencePort preferencePort,
                                DeliveryLogStore deliveryLogStore,
                                ChannelDispatcher channelDispatcher,
                                RetrySchedule retrySchedule,
                                Clock clock,
                                Sleeper sleeper) {
        this.templateResolver = templateResolver;
        this.preferencePort = preferencePort;
        this.deliveryLogStore = deliveryLogStore;
        this.channelDispatcher = channelDispatcher;
        this.retrySchedule = retrySchedule;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * Dispatches notifications for one event across all its candidate channels.
     */
    public void dispatch(NotificationEvent event) {
        RenderedMessage message = templateResolver.resolve(event);
        NotificationPreferences preferences = resolvePreferences(event.recipientUserId());

        for (NotificationChannel channel : message.channels()) {
            dispatchChannel(event, message, preferences, channel);
        }
    }

    private void dispatchChannel(NotificationEvent event, RenderedMessage message,
                                 NotificationPreferences preferences, NotificationChannel channel) {
        // Deduplicate on (kafkaEventId, recipient, channel): a redelivery already recorded is
        // discarded silently with no dispatch (Property 22).
        if (deliveryLogStore.alreadyDelivered(event.kafkaEventId(), event.recipientUserId(), channel)) {
            log.debug("Skipping already-processed delivery for event {} to user {} on channel {}",
                    event.kafkaEventId(), event.recipientUserId(), channel);
            return;
        }

        // Respect user preference: a disabled channel is skipped and recorded (Requirement 17.6).
        if (!preferences.isEnabled(channel)) {
            log.debug("Channel {} disabled by preference for event {}", channel, event.kafkaEventId());
            record(event, channel, DeliveryStatus.SKIPPED_PREFERENCE, 0, null);
            return;
        }

        // No address on this channel: retrying cannot succeed, so record the skip and move on.
        if (!channelDispatcher.canAddress(channel, event.contact())) {
            log.debug("No {} address on record for user {}; skipping that channel for event {}",
                    channel, event.recipientUserId(), event.kafkaEventId());
            record(event, channel, DeliveryStatus.SKIPPED_NO_CONTACT, 0, "no " + channel + " address on record");
            return;
        }

        attemptWithRetry(event, message, channel);
    }

    private void attemptWithRetry(NotificationEvent event, RenderedMessage message,
                                  NotificationChannel channel) {
        int maxAttempts = retrySchedule.maxAttempts();
        NotificationDeliveryException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                channelDispatcher.dispatch(channel, event, message);
                record(event, channel, DeliveryStatus.DELIVERED, attempt - 1, null);
                log.debug("Delivered event {} on channel {} on attempt {}",
                        event.kafkaEventId(), channel, attempt);
                return;
            } catch (NotificationDeliveryException ex) {
                lastFailure = ex;
                log.warn("Attempt {}/{} to deliver event {} on channel {} failed: {}",
                        attempt, maxAttempts, event.kafkaEventId(), channel, ex.getMessage());
                if (retrySchedule.shouldRetryAfterAttempt(attempt)) {
                    backoff(attempt);
                }
            }
        }

        String reason = lastFailure == null ? "unknown" : lastFailure.getMessage();
        record(event, channel, DeliveryStatus.PERMANENTLY_FAILED, maxAttempts, reason);
        log.error("Delivery of event {} on channel {} permanently failed after {} attempts",
                event.kafkaEventId(), channel, maxAttempts);
    }

    private NotificationPreferences resolvePreferences(UUID userId) {
        try {
            return preferencePort.findByUserId(userId).orElseGet(NotificationPreferences::allEnabled);
        } catch (RuntimeException lookupFailed) {
            // Preference data unavailable at dispatch time -> default to all channels enabled.
            log.warn("Preference lookup failed for user {}; defaulting to all channels enabled", userId);
            return NotificationPreferences.allEnabled();
        }
    }

    private void record(NotificationEvent event, NotificationChannel channel, DeliveryStatus status,
                        int retryCount, String errorDescription) {
        deliveryLogStore.record(new DeliveryLogEntity(
                event.kafkaEventId(), channel, event.recipientUserId(), clock.instant(),
                status, retryCount, errorDescription));
    }

    private void backoff(int failedAttempt) {
        long millis = retrySchedule.backoffAfterAttempt(failedAttempt).toMillis();
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting notification retry backoff", e);
        }
    }

    /** Seam so tests can avoid real sleeps. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    // Package-private accessor so tests can assert the effective retry schedule.
    RetrySchedule retrySchedule() {
        return retrySchedule;
    }
}
