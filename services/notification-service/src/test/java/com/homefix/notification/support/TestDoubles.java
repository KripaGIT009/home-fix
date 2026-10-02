package com.homefix.notification.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.homefix.notification.channel.EmailPort;
import com.homefix.notification.channel.InAppPort;
import com.homefix.notification.channel.NotificationDeliveryException;
import com.homefix.notification.channel.PushPort;
import com.homefix.notification.channel.SmsPort;
import com.homefix.notification.contact.ContactDirectoryPort;
import com.homefix.notification.contact.ContactLookupException;
import com.homefix.notification.delivery.DeliveryLogEntity;
import com.homefix.notification.delivery.DeliveryLogStore;
import com.homefix.notification.domain.NotificationChannel;
import com.homefix.notification.domain.NotificationContact;
import com.homefix.notification.domain.NotificationPreferences;
import com.homefix.notification.preference.NotificationPreferencePort;

/**
 * Small hand-written test doubles for the Notification Service ports (no Spring, no Mockito).
 */
public final class TestDoubles {

    private TestDoubles() {
    }

    /** In-memory {@link DeliveryLogStore} keyed on {@code (kafkaEventId, userId, channel)}. */
    public static final class InMemoryDeliveryLogStore implements DeliveryLogStore {
        private final ConcurrentHashMap<String, DeliveryLogEntity> rows = new ConcurrentHashMap<>();
        public final List<DeliveryLogEntity> records = new ArrayList<>();

        @Override
        public boolean alreadyDelivered(UUID kafkaEventId, UUID userId, NotificationChannel channel) {
            return rows.containsKey(key(kafkaEventId, userId, channel));
        }

        @Override
        public synchronized void record(DeliveryLogEntity entry) {
            // Enforce the (kafkaEventId, userId, channel) uniqueness like the real PK would.
            rows.putIfAbsent(key(entry.getKafkaEventId(), entry.getUserId(), entry.getChannel()), entry);
            records.add(entry);
        }

        private static String key(UUID eventId, UUID userId, NotificationChannel channel) {
            return eventId + "|" + userId + "|" + channel;
        }
    }

    /**
     * {@link ContactDirectoryPort} over a fixed map. Unknown users resolve to empty, like a 404
     * from the Auth Service; {@link #failing()} simulates an unreachable directory.
     */
    public static final class FakeContactDirectory implements ContactDirectoryPort {
        private final Map<UUID, NotificationContact> contacts = new HashMap<>();
        private final boolean unreachable;
        public final AtomicInteger lookups = new AtomicInteger();

        public FakeContactDirectory() {
            this(false);
        }

        private FakeContactDirectory(boolean unreachable) {
            this.unreachable = unreachable;
        }

        public static FakeContactDirectory failing() {
            return new FakeContactDirectory(true);
        }

        public FakeContactDirectory with(UUID userId, NotificationContact contact) {
            contacts.put(userId, contact);
            return this;
        }

        @Override
        public Optional<NotificationContact> findContact(UUID userId) {
            lookups.incrementAndGet();
            if (unreachable) {
                throw new ContactLookupException("simulated contact directory outage");
            }
            return Optional.ofNullable(contacts.get(userId));
        }
    }

    /** Recording {@link SmsPort} that optionally fails the first N attempts. */
    public static final class RecordingSmsPort implements SmsPort {
        private final int failuresBeforeSuccess;
        public final AtomicInteger attempts = new AtomicInteger();

        public RecordingSmsPort() {
            this(0);
        }

        public RecordingSmsPort(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public void send(String mobileNumber, String message) {
            int attempt = attempts.incrementAndGet();
            if (attempt <= failuresBeforeSuccess) {
                throw new NotificationDeliveryException("simulated SMS failure on attempt " + attempt);
            }
        }
    }

    /** {@link SmsPort} that always fails. */
    public static final class AlwaysFailingSmsPort implements SmsPort {
        public final AtomicInteger attempts = new AtomicInteger();

        @Override
        public void send(String mobileNumber, String message) {
            attempts.incrementAndGet();
            throw new NotificationDeliveryException("permanent SMS failure");
        }
    }

    /** Recording {@link EmailPort}. */
    public static final class RecordingEmailPort implements EmailPort {
        public final AtomicInteger attempts = new AtomicInteger();

        @Override
        public void send(String emailAddress, String subject, String body) {
            attempts.incrementAndGet();
        }
    }

    /** Recording {@link PushPort}. */
    public static final class RecordingPushPort implements PushPort {
        public final AtomicInteger attempts = new AtomicInteger();

        @Override
        public void send(String deviceToken, String title, String body) {
            attempts.incrementAndGet();
        }
    }

    /** Recording {@link InAppPort}. */
    public static final class RecordingInAppPort implements InAppPort {
        public final AtomicInteger attempts = new AtomicInteger();

        @Override
        public void publish(UUID userId, String title, String body) {
            attempts.incrementAndGet();
        }
    }

    /** {@link NotificationPreferencePort} returning a fixed (possibly empty) preference. */
    public static final class FixedPreferencePort implements NotificationPreferencePort {
        private final Optional<NotificationPreferences> preferences;

        private FixedPreferencePort(Optional<NotificationPreferences> preferences) {
            this.preferences = preferences;
        }

        public static FixedPreferencePort unavailable() {
            return new FixedPreferencePort(Optional.empty());
        }

        public static FixedPreferencePort of(NotificationPreferences preferences) {
            return new FixedPreferencePort(Optional.of(preferences));
        }

        @Override
        public Optional<NotificationPreferences> findByUserId(UUID userId) {
            return preferences;
        }
    }
}
