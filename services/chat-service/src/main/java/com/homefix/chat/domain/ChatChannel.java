package com.homefix.chat.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A chat channel linking the Customer and Provider on a single booking (Requirement 18.1).
 *
 * <p>The channel record stores {@code (bookingId, customerId, providerId)} — the tuple the design
 * mandates for participant-only access control (Property 23). {@code bookingCreatedAt} anchors the
 * 90-day message retention window, which is independent of channel status (Requirement 18.4).
 *
 * <p>The channel is keyed by {@code bookingId}: a booking has exactly one channel, which makes
 * activation idempotent under Kafka redelivery.
 */
@Entity
@Table(name = "chat_channel")
public class ChatChannel {

    /** Primary key; equal to the booking ID so activation is naturally idempotent. */
    @Id
    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChannelStatus status;

    /** Booking creation timestamp; the retention window is measured from here (Requirement 18.4). */
    @Column(name = "booking_created_at", nullable = false, updatable = false)
    private Instant bookingCreatedAt;

    @Column(name = "activated_at", nullable = false, updatable = false)
    private Instant activatedAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    protected ChatChannel() {
        // for JPA
    }

    private ChatChannel(UUID bookingId, UUID customerId, UUID providerId,
                        Instant bookingCreatedAt, Instant activatedAt) {
        this.bookingId = bookingId;
        this.customerId = customerId;
        this.providerId = providerId;
        this.bookingCreatedAt = bookingCreatedAt;
        this.activatedAt = activatedAt;
        this.status = ChannelStatus.ACTIVE;
    }

    /** Activates a new channel in {@link ChannelStatus#ACTIVE} (Requirement 18.1). */
    public static ChatChannel activate(UUID bookingId, UUID customerId, UUID providerId,
                                       Instant bookingCreatedAt, Instant activatedAt) {
        return new ChatChannel(bookingId, customerId, providerId, bookingCreatedAt, activatedAt);
    }

    /**
     * Marks the channel deactivated (Requirement 18.5). Idempotent: re-deactivating an already
     * deactivated channel keeps the original {@code deactivatedAt}.
     */
    public void deactivate(Instant when) {
        if (this.status == ChannelStatus.DEACTIVATED) {
            return;
        }
        this.status = ChannelStatus.DEACTIVATED;
        this.deactivatedAt = when;
    }

    public boolean isActive() {
        return status == ChannelStatus.ACTIVE;
    }

    /** The two users permitted to participate in this channel (Property 23). */
    public ChannelParticipants participants() {
        return new ChannelParticipants(customerId, providerId);
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public ChannelStatus getStatus() {
        return status;
    }

    public Instant getBookingCreatedAt() {
        return bookingCreatedAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Instant getDeactivatedAt() {
        return deactivatedAt;
    }
}
