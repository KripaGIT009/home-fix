package com.homefix.dispatch.service.fake;

import com.homefix.dispatch.port.AcceptanceLedger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory {@link AcceptanceLedger} that records each announced {@code ProviderAccepted} instead
 * of writing an outbox row, so the dispatch loop and the reconciler can be tested without a
 * database (Requirement 8.6). Every entry is always due. Can be told to fail opening or announcing,
 * as an unavailable database would.
 */
public class InMemoryAcceptanceLedger implements AcceptanceLedger {

    /** One {@code ProviderAccepted} that would have been written to the outbox. */
    public record Announcement(UUID bookingId, UUID customerId, UUID providerId, Instant bookingCreatedAt) {
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final List<Announcement> announcements = new ArrayList<>();
    private final List<UUID> discarded = new ArrayList<>();
    private final List<UUID> postponed = new ArrayList<>();
    private boolean failOpen;
    private boolean failAnnounce;

    public InMemoryAcceptanceLedger failOpening() {
        this.failOpen = true;
        return this;
    }

    /** Makes {@link #announce} throw until {@link #recover()} is called. */
    public InMemoryAcceptanceLedger failAnnouncing() {
        this.failAnnounce = true;
        return this;
    }

    public InMemoryAcceptanceLedger recover() {
        this.failOpen = false;
        this.failAnnounce = false;
        return this;
    }

    @Override
    public synchronized void open(UUID bookingId, UUID customerId, UUID providerId, Instant bookingCreatedAt) {
        if (failOpen) {
            throw new IllegalStateException("database unavailable");
        }
        entries.put(bookingId, new Entry(bookingId, customerId, providerId, bookingCreatedAt, false, 0));
    }

    @Override
    public synchronized void markBookingAccepted(UUID bookingId) {
        entries.computeIfPresent(bookingId, (id, e) -> new Entry(e.bookingId(), e.customerId(),
                e.providerId(), e.bookingCreatedAt(), true, e.attempts()));
    }

    @Override
    public synchronized boolean announce(UUID bookingId) {
        if (failAnnounce) {
            throw new IllegalStateException("database unavailable");
        }
        Entry entry = entries.remove(bookingId);
        if (entry == null) {
            return false;
        }
        announcements.add(new Announcement(entry.bookingId(), entry.customerId(), entry.providerId(),
                entry.bookingCreatedAt()));
        return true;
    }

    @Override
    public synchronized void discard(UUID bookingId) {
        entries.remove(bookingId);
        discarded.add(bookingId);
    }

    @Override
    public synchronized void postpone(UUID bookingId, String error) {
        entries.computeIfPresent(bookingId, (id, e) -> new Entry(e.bookingId(), e.customerId(),
                e.providerId(), e.bookingCreatedAt(), e.bookingAccepted(), e.attempts() + 1));
        postponed.add(bookingId);
    }

    @Override
    public synchronized List<Entry> due(int limit) {
        return entries.values().stream().limit(limit).toList();
    }

    /** The {@code ProviderAccepted} events written, in order. */
    public synchronized List<Announcement> announcements() {
        return List.copyOf(announcements);
    }

    public synchronized boolean published() {
        return !announcements.isEmpty();
    }

    /** Entries still outstanding, keyed by booking. */
    public synchronized Map<UUID, Entry> outstanding() {
        return Map.copyOf(entries);
    }

    public synchronized List<UUID> discarded() {
        return List.copyOf(discarded);
    }

    public synchronized List<UUID> postponed() {
        return List.copyOf(postponed);
    }
}
