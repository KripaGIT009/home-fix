package com.homefix.location.support;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.homefix.location.domain.TerminatedBooking;
import com.homefix.location.domain.TerminatedBookingRepository;

/**
 * In-memory stand-in for {@link TerminatedBookingRepository} so {@code LocationService} can be
 * unit-tested without a database. Only the methods exercised by the service —
 * {@code existsByBookingId}, {@code save}, and {@code count} — carry real behaviour; the
 * remaining {@code JpaRepository} contract methods are unused in these tests.
 */
public abstract class AbstractInMemoryTerminatedRepository implements TerminatedBookingRepository {

    private final Map<UUID, TerminatedBooking> byBookingId = new HashMap<>();

    @Override
    public boolean existsByBookingId(UUID bookingId) {
        return byBookingId.containsKey(bookingId);
    }

    @Override
    public <S extends TerminatedBooking> S save(S entity) {
        byBookingId.put(entity.getBookingId(), entity);
        return entity;
    }

    @Override
    public long count() {
        return byBookingId.size();
    }

    // ---- unused JpaRepository methods ----
    @Override public java.util.List<TerminatedBooking> findAll() { throw new UnsupportedOperationException(); }
    @Override public java.util.List<TerminatedBooking> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
    @Override public java.util.List<TerminatedBooking> findAllById(Iterable<UUID> ids) { throw new UnsupportedOperationException(); }
    @Override public <S extends TerminatedBooking> java.util.List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
    @Override public void flush() { }
    @Override public <S extends TerminatedBooking> S saveAndFlush(S entity) { return save(entity); }
    @Override public <S extends TerminatedBooking> java.util.List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
    @Override public void deleteAllInBatch(Iterable<TerminatedBooking> entities) { }
    @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) { }
    @Override public void deleteAllInBatch() { }
    @Override public TerminatedBooking getOne(UUID id) { throw new UnsupportedOperationException(); }
    @Override public TerminatedBooking getById(UUID id) { throw new UnsupportedOperationException(); }
    @Override public TerminatedBooking getReferenceById(UUID id) { throw new UnsupportedOperationException(); }
    @Override public <S extends TerminatedBooking> java.util.Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
    @Override public <S extends TerminatedBooking> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
    @Override public <S extends TerminatedBooking> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
    @Override public <S extends TerminatedBooking> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
    @Override public <S extends TerminatedBooking> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
    @Override public <S extends TerminatedBooking> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
    @Override public <S extends TerminatedBooking, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
    @Override public java.util.Optional<TerminatedBooking> findById(UUID id) { throw new UnsupportedOperationException(); }
    @Override public boolean existsById(UUID id) { return false; }
    @Override public void deleteById(UUID id) { byBookingId.remove(id); }
    @Override public void delete(TerminatedBooking entity) { byBookingId.remove(entity.getBookingId()); }
    @Override public void deleteAllById(Iterable<? extends UUID> ids) { }
    @Override public void deleteAll(Iterable<? extends TerminatedBooking> entities) { }
    @Override public void deleteAll() { byBookingId.clear(); }
    @Override public org.springframework.data.domain.Page<TerminatedBooking> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
}
