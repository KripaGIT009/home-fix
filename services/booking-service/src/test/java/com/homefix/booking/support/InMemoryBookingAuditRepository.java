package com.homefix.booking.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;

import com.homefix.booking.domain.BookingAudit;
import com.homefix.booking.domain.BookingAuditRepository;

/**
 * Minimal in-memory {@link BookingAuditRepository} for service unit / property tests, so audit
 * rows persist across calls without a database or Spring context. Only the methods used by
 * {@code BookingTransitionService} and the property tests are implemented; the rest throw
 * {@link UnsupportedOperationException}.
 */
public class InMemoryBookingAuditRepository implements BookingAuditRepository {

    private final Map<UUID, BookingAudit> store = new LinkedHashMap<>();

    /** All audit rows in insertion order. */
    public List<BookingAudit> all() {
        return new ArrayList<>(store.values());
    }

    /** Convenience alias used by tests. */
    public List<BookingAudit> byBooking(UUID bookingId) {
        return findByBookingIdOrderByTransitionedAtAsc(bookingId);
    }

    @Override
    public List<BookingAudit> findByBookingIdOrderByTransitionedAtAsc(UUID bookingId) {
        List<BookingAudit> result = new ArrayList<>();
        for (BookingAudit a : store.values()) {
            if (a.getBookingId().equals(bookingId)) {
                result.add(a);
            }
        }
        // Stable ordering: by timestamp, preserving insertion order for equal timestamps.
        result.sort(Comparator.comparing(BookingAudit::getTransitionedAt));
        return result;
    }

    @Override
    public <S extends BookingAudit> S save(S entity) {
        store.put(entity.getId(), entity);
        return entity;
    }

    @Override
    public <S extends BookingAudit> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>();
        entities.forEach(e -> saved.add(save(e)));
        return saved;
    }

    @Override
    public Optional<BookingAudit> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public boolean existsById(UUID id) {
        return store.containsKey(id);
    }

    @Override
    public List<BookingAudit> findAll() {
        return all();
    }

    @Override
    public long count() {
        return store.size();
    }

    // ---- Unused JpaRepository surface ----

    @Override
    public List<BookingAudit> findAll(Sort sort) {
        return all();
    }

    @Override
    public List<BookingAudit> findAllById(Iterable<UUID> ids) {
        List<BookingAudit> result = new ArrayList<>();
        ids.forEach(id -> findById(id).ifPresent(result::add));
        return result;
    }

    @Override
    public void deleteById(UUID id) {
        store.remove(id);
    }

    @Override
    public void delete(BookingAudit entity) {
        store.remove(entity.getId());
    }

    @Override
    public void deleteAllById(Iterable<? extends UUID> ids) {
        ids.forEach(store::remove);
    }

    @Override
    public void deleteAll(Iterable<? extends BookingAudit> entities) {
        entities.forEach(this::delete);
    }

    @Override
    public void deleteAll() {
        store.clear();
    }

    @Override
    public void flush() {
    }

    @Override
    public <S extends BookingAudit> S saveAndFlush(S entity) {
        return save(entity);
    }

    @Override
    public <S extends BookingAudit> List<S> saveAllAndFlush(Iterable<S> entities) {
        return saveAll(entities);
    }

    @Override
    public void deleteAllInBatch(Iterable<BookingAudit> entities) {
        deleteAll(entities);
    }

    @Override
    public void deleteAllByIdInBatch(Iterable<UUID> ids) {
        deleteAllById(ids);
    }

    @Override
    public void deleteAllInBatch() {
        deleteAll();
    }

    @Override
    public BookingAudit getOne(UUID id) {
        return getReferenceById(id);
    }

    @Override
    public BookingAudit getById(UUID id) {
        return getReferenceById(id);
    }

    @Override
    public BookingAudit getReferenceById(UUID id) {
        return findById(id).orElseThrow();
    }

    @Override
    public <S extends BookingAudit> Optional<S> findOne(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends BookingAudit> List<S> findAll(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends BookingAudit> List<S> findAll(Example<S> example, Sort sort) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends BookingAudit> Page<S> findAll(Example<S> example, Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends BookingAudit> long count(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends BookingAudit> boolean exists(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Page<BookingAudit> findAll(Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends BookingAudit, R> R findBy(Example<S> example,
            java.util.function.Function<FetchableFluentQuery<S>, R> queryFunction) {
        throw new UnsupportedOperationException();
    }
}
