package com.homefix.chat.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.homefix.chat.delivery.MessageDeliveryPort;
import com.homefix.chat.domain.ChatChannel;
import com.homefix.chat.domain.ChatMessage;
import com.homefix.chat.domain.PhoneNumberMasker;
import com.homefix.chat.presence.PresenceRegistry;
import com.homefix.chat.push.NotificationPushPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core in-app chat orchestration (Requirement 18).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li><b>Channel lifecycle</b> — {@link #activateChannel} on PROVIDER_ACCEPTED (18.1) and
 *       {@link #deactivateChannel} on PAYMENT_COMPLETED / CANCELLED (18.5); both are idempotent so
 *       Kafka redelivery is harmless.</li>
 *   <li><b>Access control</b> — every send/read is checked against the channel's stored
 *       participants; non-participants are rejected with 403 (18.7, Property 23).</li>
 *   <li><b>Deactivation guard</b> — sends to a deactivated channel are rejected with a descriptive
 *       error (18.6); historical reads remain allowed.</li>
 *   <li><b>Phone masking</b> — message bodies are masked before they are stored or delivered so a
 *       personal mobile number is never exposed (18.8).</li>
 *   <li><b>Retention</b> — each message records {@code retainUntil = bookingCreatedAt + retention}
 *       (default 90 days), independent of channel status (18.4).</li>
 *   <li><b>Real-time delivery + offline push</b> — the message is delivered over WebSocket and,
 *       when the recipient is offline, a push notification is raised via the Notification Service
 *       (18.2, 18.3).</li>
 * </ul>
 *
 * <p>Collaborators are injected as ports so the whole class is unit-testable with hand-written
 * doubles and no Spring context. A {@link Clock} makes retention and timestamps deterministic in
 * tests. Message bodies and phone numbers are never logged (Requirement 26.4).
 */
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatStore store;
    private final MessageDeliveryPort delivery;
    private final PresenceRegistry presence;
    private final NotificationPushPort push;
    private final Duration retention;
    private final Clock clock;

    public ChatService(ChatStore store,
                       MessageDeliveryPort delivery,
                       PresenceRegistry presence,
                       NotificationPushPort push,
                       Duration retention,
                       Clock clock) {
        this.store = store;
        this.delivery = delivery;
        this.presence = presence;
        this.push = push;
        this.retention = retention;
        this.clock = clock;
    }

    /**
     * Activates the chat channel for a booking on transition to PROVIDER_ACCEPTED
     * (Requirement 18.1). Idempotent: if a channel already exists for the booking it is returned
     * unchanged, so a redelivered {@code ProviderAccepted} event does not create a duplicate.
     *
     * <p>"Unchanged" includes a DEACTIVATED channel: the booking has already ended (its terminal
     * event was consumed first and left a tombstone, or the channel was closed after a genuine
     * activation), and a late or replayed acceptance must not reopen it. If a tombstone is written
     * concurrently, the insert here fails on the primary key (see {@link ChatChannel}) and the
     * consumer's retry finds the tombstone.
     */
    @Transactional
    public ChatChannel activateChannel(UUID bookingId, UUID customerId, UUID providerId,
                                       Instant bookingCreatedAt) {
        return store.findChannel(bookingId).orElseGet(() -> {
            ChatChannel channel = ChatChannel.activate(
                    bookingId, customerId, providerId, bookingCreatedAt, clock.instant());
            ChatChannel saved = store.saveChannel(channel);
            log.info("Activated chat channel for booking {}", bookingId);
            return saved;
        });
    }

    /**
     * Deactivates the chat channel for a booking on transition to PAYMENT_COMPLETED or CANCELLED
     * (Requirement 18.5). Idempotent: an already deactivated channel keeps its original
     * {@code deactivatedAt}, so a redelivered terminal event is harmless.
     *
     * <p>If no channel exists yet, a DEACTIVATED tombstone is recorded in its place, built from
     * what the terminal event carries, so a {@code ProviderAccepted} consumed after it (the topics
     * are not ordered with respect to each other) cannot open a channel on an ended booking. Missing
     * participant ids are stored as {@link ChatChannel#UNKNOWN_PARTICIPANT}; a missing creation
     * time falls back to now.
     *
     * @param customerId       the booking's customer, if the event carried it
     * @param providerId       the booking's provider, if one was assigned and the event carried it
     * @param bookingCreatedAt the booking's creation time, if the event carried it
     */
    @Transactional
    public void deactivateChannel(UUID bookingId, UUID customerId, UUID providerId,
                                  Instant bookingCreatedAt) {
        Instant now = clock.instant();
        store.findChannel(bookingId).ifPresentOrElse(channel -> {
            if (channel.isActive()) {
                channel.deactivate(now);
                store.saveChannel(channel);
                log.info("Deactivated chat channel for booking {}", bookingId);
            }
        }, () -> {
            store.saveChannel(ChatChannel.tombstone(
                    bookingId, customerId, providerId, bookingCreatedAt, now));
            log.info("Recorded a deactivated chat channel for booking {}, which ended before its "
                    + "channel was activated", bookingId);
        });
    }

    /**
     * Sends a message from {@code senderId} on {@code bookingId}.
     *
     * @throws ChatException 404 if no channel exists, 403 if the sender is not a participant
     *                       (Property 23), 409 if the channel is deactivated (18.6), 400 if the
     *                       body is blank.
     */
    @Transactional
    public ChatMessage sendMessage(UUID bookingId, UUID senderId, String rawBody) {
        ChatChannel channel = requireChannel(bookingId);
        requireParticipant(channel, senderId);

        if (!channel.isActive()) {
            throw ChatException.channelDeactivated(
                    "chat channel for this booking is no longer active");
        }
        if (rawBody == null || rawBody.isBlank()) {
            throw ChatException.validation("message body must not be blank");
        }

        String maskedBody = PhoneNumberMasker.mask(rawBody);
        Instant now = clock.instant();
        Instant retainUntil = channel.getBookingCreatedAt().plus(retention);
        ChatMessage message = store.saveMessage(
                ChatMessage.create(bookingId, senderId, maskedBody, now, retainUntil));

        UUID recipientId = recipientOf(channel, senderId);

        // Real-time delivery over WebSocket (Requirement 18.2).
        delivery.deliver(message);

        // Offline recipient -> push notification via Notification Service (Requirement 18.3).
        if (!presence.isOnline(bookingId, recipientId)) {
            push.notifyUnreadMessage(recipientId, bookingId);
        }

        return message;
    }

    /**
     * Returns the chronological message history for a booking.
     *
     * @throws ChatException 404 if no channel exists, 403 if the reader is not a participant
     *                       (Property 23). History is available regardless of channel status so
     *                       that retained messages remain readable (Requirement 18.4).
     */
    @Transactional(readOnly = true)
    public List<ChatMessage> readMessages(UUID bookingId, UUID readerId) {
        ChatChannel channel = requireChannel(bookingId);
        requireParticipant(channel, readerId);
        return store.findMessages(bookingId);
    }

    private ChatChannel requireChannel(UUID bookingId) {
        return store.findChannel(bookingId)
                .orElseThrow(() -> ChatException.channelNotFound(
                        "no chat channel exists for the requested booking"));
    }

    /** Enforces Property 23: only the booking's customer or provider may proceed. */
    private void requireParticipant(ChatChannel channel, UUID userId) {
        if (!channel.participants().isParticipant(userId)) {
            throw ChatException.forbidden(
                    "requesting user is not a participant of this chat channel");
        }
    }

    private static UUID recipientOf(ChatChannel channel, UUID senderId) {
        return senderId.equals(channel.getCustomerId())
                ? channel.getProviderId()
                : channel.getCustomerId();
    }
}
