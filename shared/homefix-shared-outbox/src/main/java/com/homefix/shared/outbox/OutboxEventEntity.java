package com.homefix.shared.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 * {@code id, aggregate_type, aggregate_id, event_type, payload, status, created_at, published_at},
 * plus the relay's bookkeeping columns {@code retry_count}, {@code next_attempt_at},
 * {@code last_error} and {@code version}.
 *
 * <p><b>Relay scheduling.</b> A PENDING row is <em>due</em> when {@code next_attempt_at} is null
 * or in the past. The relay uses that one column for two things (Requirement 22.4):
 * <ul>
 *   <li>a <em>claim lease</em> — claiming a row pushes {@code next_attempt_at} a short lease into
 *       the future, so no other relay instance picks it up while it is being published;</li>
 *   <li>the <em>persisted backoff</em> — a failed publish sets it to the time of the next retry,
 *       instead of the relay sleeping in-line.</li>
 * </ul>
 * Producers never touch it: a freshly written row has it null and is due immediately.
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
     * Serialised event payload (typically JSON). Stored as a plain {@code text} column.
     *
     * <p>Deliberately not {@code @Lob}. On PostgreSQL Hibernate maps {@code @Lob String} to
     * {@code oid} -- a large-object reference, not inline text -- and the relay then reads the
     * payload through the large-object API. Every poll cycle died with "Unable to access lob
     * stream" because the stream is only valid inside the transaction that opened it, so no
     * event was ever published and rows simply accumulated as PENDING with no error recorded
     * against them. {@code text} holds a JSON document of any size we produce and reads back as
     * an ordinary string.
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "payload", nullable = false, columnDefinition = "text")
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
     * Earliest time the relay may (re)attempt this row; null means "due now". Holds either the
     * claim lease of the relay currently publishing the row or the next retry time after a failed
     * attempt (see the class comment).
     *
     * <p>Deliberately nullable. Compose runs producers with {@code ddl-auto: update}, which adds
     * this column to an existing table with {@code ALTER TABLE ... ADD COLUMN}; a {@code NOT NULL}
     * column without a default cannot be added to a table that already holds rows, and Hibernate
     * only logs that failure. Null-as-due avoids needing a default or a backfill.
     */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    /**
     * Optimistic-lock guard. Every relay write (claim, outcome) bumps it, so a relay whose claim
     * lease expired and was taken over by another instance fails its stale outcome write instead
     * of silently overwriting the new owner's state.
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
        this.nextAttemptAt = null;
    }

    /**
     * Records a failed relay attempt, incrementing the retry counter and capturing the error.
     * After this call {@link #getRetryCount()} is the number of publish attempts that have failed.
     */
    public void markFailedAttempt(String error) {
        this.retryCount++;
        this.lastError = truncate(error);
    }

    /**
     * Records a publish attempt that failed for a reason outside the event itself (the broker
     * unreachable or slow), capturing the error <em>without</em> spending an attempt of the retry
     * budget: an outage must not walk the oldest rows to FAILED.
     */
    public void recordTransientFailure(String error) {
        this.lastError = truncate(error);
    }

    /**
     * Sets the earliest time the relay may next attempt this row: a claim lease while it is being
     * published, the backoff deadline after a failed attempt, or "now" to release a claim.
     */
    public void scheduleNextAttempt(Instant when) {
        this.nextAttemptAt = when;
    }

    /**
     * Marks the row permanently failed after the retry budget is exhausted. The row is no longer
     * due; an operator re-queues it by setting it back to PENDING.
     */
    public void markFailed(String error) {
        this.status = OutboxEventStatus.FAILED;
        this.lastError = truncate(error);
        this.nextAttemptAt = null;
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

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public long getVersion() {
        return version;
    }
}
