package com.homefix.chat.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A single stored chat message (Requirement 18.4).
 *
 * <p>Messages are persisted for at least 90 days from the owning booking's creation date,
 * regardless of channel status, so {@code retainUntil} is stored explicitly on each row to make the
 * retention guarantee auditable and to drive purge jobs. The message {@code body} is stored already
 * phone-masked (Requirement 18.8); raw personal phone numbers are never persisted or logged.
 */
@Entity
@Table(name = "chat_message", indexes = {
        @Index(name = "idx_chat_message_booking", columnList = "booking_id, sent_at"),
        @Index(name = "idx_chat_message_retain_until", columnList = "retain_until")
})
public class ChatMessage {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "sender_id", nullable = false, updatable = false)
    private UUID senderId;

    /** Phone-masked message text (Requirement 18.8). */
    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    /**
     * Earliest instant at which this message becomes eligible for purge. Equal to
     * {@code bookingCreatedAt + retention} (Requirement 18.4).
     */
    @Column(name = "retain_until", nullable = false, updatable = false)
    private Instant retainUntil;

    protected ChatMessage() {
        // for JPA
    }

    private ChatMessage(UUID id, UUID bookingId, UUID senderId, String body,
                        Instant sentAt, Instant retainUntil) {
        this.id = id;
        this.bookingId = bookingId;
        this.senderId = senderId;
        this.body = body;
        this.sentAt = sentAt;
        this.retainUntil = retainUntil;
    }

    public static ChatMessage create(UUID bookingId, UUID senderId, String maskedBody,
                                     Instant sentAt, Instant retainUntil) {
        return new ChatMessage(UUID.randomUUID(), bookingId, senderId, maskedBody, sentAt, retainUntil);
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public String getBody() {
        return body;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getRetainUntil() {
        return retainUntil;
    }
}
