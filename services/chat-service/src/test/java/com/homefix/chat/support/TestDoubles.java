package com.homefix.chat.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.homefix.chat.delivery.MessageDeliveryPort;
import com.homefix.chat.domain.ChatChannel;
import com.homefix.chat.domain.ChatMessage;
import com.homefix.chat.presence.PresenceRegistry;
import com.homefix.chat.push.NotificationPushPort;
import com.homefix.chat.service.ChatStore;

/**
 * Small hand-written test doubles for the Chat Service ports (no Spring, no Mockito).
 */
public final class TestDoubles {

    private TestDoubles() {
    }

    /** In-memory {@link ChatStore}: one channel per bookingId, plus an ordered message list. */
    public static final class InMemoryChatStore implements ChatStore {
        private final ConcurrentHashMap<UUID, ChatChannel> channels = new ConcurrentHashMap<>();
        public final List<ChatMessage> messages = new ArrayList<>();

        @Override
        public Optional<ChatChannel> findChannel(UUID bookingId) {
            return Optional.ofNullable(channels.get(bookingId));
        }

        @Override
        public ChatChannel saveChannel(ChatChannel channel) {
            channels.put(channel.getBookingId(), channel);
            return channel;
        }

        @Override
        public synchronized ChatMessage saveMessage(ChatMessage message) {
            messages.add(message);
            return message;
        }

        @Override
        public List<ChatMessage> findMessages(UUID bookingId) {
            return messages.stream()
                    .filter(m -> m.getBookingId().equals(bookingId))
                    .sorted((a, b) -> a.getSentAt().compareTo(b.getSentAt()))
                    .toList();
        }
    }

    /** Recording {@link MessageDeliveryPort}. */
    public static final class RecordingDeliveryPort implements MessageDeliveryPort {
        public final List<ChatMessage> delivered = new ArrayList<>();

        @Override
        public void deliver(ChatMessage message) {
            delivered.add(message);
        }
    }

    /** {@link PresenceRegistry} whose online/offline verdict is fixed for the test. */
    public static final class FixedPresenceRegistry implements PresenceRegistry {
        private final boolean online;

        private FixedPresenceRegistry(boolean online) {
            this.online = online;
        }

        public static FixedPresenceRegistry allOnline() {
            return new FixedPresenceRegistry(true);
        }

        public static FixedPresenceRegistry allOffline() {
            return new FixedPresenceRegistry(false);
        }

        @Override
        public void markOnline(UUID bookingId, UUID userId) {
        }

        @Override
        public void markOffline(UUID bookingId, UUID userId) {
        }

        @Override
        public boolean isOnline(UUID bookingId, UUID userId) {
            return online;
        }
    }

    /** Recording {@link NotificationPushPort} capturing offline-recipient pushes. */
    public static final class RecordingPushPort implements NotificationPushPort {
        public final AtomicInteger pushes = new AtomicInteger();
        public volatile UUID lastRecipient;
        public volatile UUID lastBooking;

        @Override
        public void notifyUnreadMessage(UUID recipientId, UUID bookingId) {
            pushes.incrementAndGet();
            this.lastRecipient = recipientId;
            this.lastBooking = bookingId;
        }
    }
}
