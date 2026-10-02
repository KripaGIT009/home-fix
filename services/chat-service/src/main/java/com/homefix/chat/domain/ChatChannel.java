package com.homefix.chat.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * A chat channel linking the Customer and Provider on a single booking (Requirement 18.1).
 *
 * <p>The channel record stores {@code (bookingId, customerId, providerId)} — the tuple the design
 * mandates for participant-only access control (Property 23). {@code bookingCreatedAt} anchors the
 * 90-day message retention window, which is independent of channel status (Requirement 18.4).
 *
 * <p>The channel is keyed by {@code bookingId}: a booking has exactly one channel, which makes
 * activation idempotent under Kafka redelivery.
 *
 * <p><b>Tombstones.</b> {@code ProviderAccepted} and {@code BookingCancelled} travel on different
 * topics, so a cancellation can be consumed before the acceptance that preceded it. A deactivation
 * that finds no channel therefore writes a {@link #tombstone} (a row born DEACTIVATED), and a later
 * activation finds it and leaves it alone; otherwise the late acceptance would open a live channel
 * on a cancelled booking.
 *
 * <p><b>Insert, never merge.</b> The id is assigned, so by default Spring Data's {@code save} would
 * {@code merge} a new channel: if a tombstone landed between an activation's "no channel yet" read
 * and its write, the merge would update the tombstone back to ACTIVE. Implementing
 * {@link Persistable} with {@link #isNew()} true for a freshly constructed channel makes
 * {@code save} {@code persist} it instead, so the slower of two concurrent first writes fails on the
 * primary key, its consumer retries, and the retry sees the row the faster one wrote.
 */
@Entity
@Table(name = "chat_channel")
public class ChatChannel implements Persistable<UUID> {

    /** The nil UUID, standing in for a participant id the terminal event did not carry. */
    public static final UUID UNKNOWN_PARTICIPANT = new UUID(0L, 0L);

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

    /**
     * True until the row is known to exist: set for a channel built by a factory method, cleared
     * once JPA has loaded or persisted it. Not a column.
     */
    @Transient
    private boolean newChannel;

    protected ChatChannel() {
        // for JPA; a loaded channel is not new (see markPersisted)
    }

    private ChatChannel(UUID bookingId, UUID customerId, UUID providerId,
                        Instant bookingCreatedAt, Instant activatedAt) {
        this.bookingId = bookingId;
        this.customerId = customerId;
        this.providerId = providerId;
        this.bookingCreatedAt = bookingCreatedAt;
        this.activatedAt = activatedAt;
        this.status = ChannelStatus.ACTIVE;
        this.newChannel = true;
    }

    /** Activates a new channel in {@link ChannelStatus#ACTIVE} (Requirement 18.1). */
    public static ChatChannel activate(UUID bookingId, UUID customerId, UUID providerId,
                                       Instant bookingCreatedAt, Instant activatedAt) {
        return new ChatChannel(bookingId, customerId, providerId, bookingCreatedAt, activatedAt);
    }

    /**
     * A channel that was never active: recorded when a booking ends before its channel was
     * activated, so a late activation cannot open it (see the class Javadoc). The participant
     * columns are NOT NULL; a participant the terminal event did not name (the provider of a booking
     * cancelled before assignment) is stored as {@link #UNKNOWN_PARTICIPANT}, which matches no
     * user. Having never been opened, the channel's {@code activatedAt} is simply when the
     * tombstone was written.
     */
    public static ChatChannel tombstone(UUID bookingId, UUID customerId, UUID providerId,
                                        Instant bookingCreatedAt, Instant deactivatedAt) {
        ChatChannel channel = new ChatChannel(bookingId, orNil(customerId), orNil(providerId),
                bookingCreatedAt != null ? bookingCreatedAt : deactivatedAt, deactivatedAt);
        channel.deactivate(deactivatedAt);
        return channel;
    }

    private static UUID orNil(UUID id) {
        return id != null ? id : UNKNOWN_PARTICIPANT;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.newChannel = false;
    }

    @Override
    public UUID getId() {
        return bookingId;
    }

    /** {@inheritDoc} True only for a channel built here and not yet written. */
    @Override
    public boolean isNew() {
        return newChannel;
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
