package com.homefix.shared.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity backing the transactional outbox table (Task 6).
 *
 * <p>The row is written within the <em>same</em> {@code @Transactional} boundary as the
 * business state change so that either both commit or neither does. A separate Outbox
 * Processor later polls {@link OutboxEventStatus#PENDING} rows, publishes them to Kafka,
 * and marks them {@link OutboxEventStatus#PUBLISHED}. This mirrors the design's
 * {@code OUTBOX_EVENT} schema:
 * {@code id, aggregate_type, aggregate_id, event_type, payload, status, created_at, published_at}.
 */
@Entity
@Table(name = "outbox_event", schema = OutboxEventEntity.SCHEMA, indexes = {
        @Index(name = "idx_outbox_status_created", columnList = "status, created_at")
})
public class OutboxEventEntity {

    /**
     * Schema holding the outbox tables, shared by every producer and the Outbox Processor.
     *
     * <p>This is pinned rather than inherited from each service's {@code default_schema} on purpose.
     * The outbox is infrastructure, not domain data: a producer writes a row and exactly one relay
     * drains it. When the table inherited the owning service's schema, each producer wrote to its own
     * copy ({@code booking.outbox_event}, {@code payment.outbox_event}, and so on) while the relay
     * had no schema configured and so polled {@code public.outbox_event}, which no producer ever
     * wrote to. The result was that no domain event was published at all.
     *
     * <p>Atomicity is unaffected: this is the same database and the same transaction as the domain
     * write, so the guarantee that an event row commits if and only if the state change commits still
     * holds. Only the schema differs. The schema must exist before a service starts; the local stack
     * creates it in {@code docker/init-db.sql}.
     */
    public static final String SCHEMA = "outbox";

    /**
     * Primary key. This value is also used as the Kafka {@code eventId} header so that
     * downstream consumers can deduplicate redeliveries against a single stable identifier.
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 150)
    private String eventType;

    /**
     * Serialised event payload (typically JSON). Stored as a large text/JSON column.
     */
    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxEventStatus status = OutboxEventStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    /**
     * Optimistic-lock guard so concurrent Outbox Processor instances cannot double-publish
     * the same row.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected OutboxEventEntity() {
        // JPA
    }

    public OutboxEventEntity(UUID id,
                             String aggregateType,
                             UUID aggregateId,
                             String eventType,
                             String payload) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxEventStatus.PENDING;
        this.retryCount = 0;
        this.createdAt = Instant.now();
    }

    /**
     * Factory that assigns a fresh random event ID.
     */
    public static OutboxEventEntity newEvent(String aggregateType,
                                             UUID aggregateId,
                                             String eventType,
                                             String payload) {
        return new OutboxEventEntity(UUID.randomUUID(), aggregateType, aggregateId, eventType, payload);
    }

    /**
     * Marks the row published and stamps the publication time.
     */
    public void markPublished(Instant when) {
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = when;
        this.lastError = null;
    }

    /**
     * Records a failed relay attempt, incrementing the retry counter and capturing the error.
     */
    public void markFailedAttempt(String error) {
        this.retryCount++;
        this.lastError = truncate(error);
    }

    /**
     * Marks the row permanently failed after the retry budget is exhausted.
     */
    public void markFailed(String error) {
        this.status = OutboxEventStatus.FAILED;
        this.lastError = truncate(error);
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 2000 ? error : error.substring(0, 2000);
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxEventStatus getStatus() {
        return status;
    }

    public void setStatus(OutboxEventStatus status) {
        this.status = status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public long getVersion() {
        return version;
    }
}
