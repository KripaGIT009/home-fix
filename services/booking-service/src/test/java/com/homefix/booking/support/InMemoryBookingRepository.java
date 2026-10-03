package com.homefix.booking.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStatus;

/**
 * Minimal in-memory {@link BookingRepository} for service and web-layer tests of the read side,
 * so bookings persist across calls without a database or Spring context. The derived queries
 * reproduce the ordering their method names promise (the real SQL is exercised by
 * {@code BookingRepositoryPagingTest}); the rest of the {@code JpaRepository} surface throws
 * {@link UnsupportedOperationException}.
 */
public class InMemoryBookingRepository implements BookingRepository {

    /**
     * {@code findByCustomerIdOrderByCreatedAtDescIdDesc}'s order. UUID's own {@code compareTo}
     * compares signed longs, which is not the database's byte order; any total order serves as a
     * tie-breaker, so tests must not depend on which of two same-instant bookings comes first.
     */
    private static final Comparator<Booking> NEWEST_FIRST =
            Comparator.comparing(Booking::getCreatedAt).thenComparing(Booking::getId).reversed();

    private final Map<UUID, Booking> store = new LinkedHashMap<>();

    /** Candidate_Tenants for {@link #findAssignmentQueue}; none unless a test supplies them. */
    private final InMemoryBookingTenantCandidateRepository candidates;

    public InMemoryBookingRepository() {
        this(new InMemoryBookingTenantCandidateRepository());
    }

    public InMemoryBookingRepository(InMemoryBookingTenantCandidateRepository candidates) {
        this.candidates = candidates;
    }

    @Override
    public List<Booking> findAssignmentQueue(UUID tenantId, Pageable pageable) {
        return store.values().stream()
                .filter(b -> b.getStatus() == BookingStatus.AWAITING_ASSIGNMENT
                        && (tenantId.equals(b.getTenantId())
                            || (b.getTenantId() == null
                                && candidates.existsByBookingIdAndTenantId(b.getId(), tenantId))))
                .sorted(Comparator.comparing(Booking::getQueuedForAssignmentAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .skip(pageable.getOffset())
                .limit(pageable.getPageSize())
                .toList();
    }

    @Override
    public List<Booking> findByTenantIdAndStatusInOrderByCreatedAtDescIdDesc(
            UUID tenantId, Collection<BookingStatus> statuses, Pageable pageable) {
        return store.values().stream()
                .filter(b -> tenantId.equals(b.getTenantId()) && statuses.contains(b.getStatus()))
                .sorted(NEWEST_FIRST)
                .skip(pageable.getOffset())
                .limit(pageable.getPageSize())
                .toList();
    }

    @Override
    public List<Booking> findByStatusInAndQueuedForAssignmentAtBeforeOrderByQueuedForAssignmentAtAsc(
            Collection<BookingStatus> statuses, Instant cutoff, Pageable pageable) {
        return store.values().stream()
                .filter(b -> statuses.contains(b.getStatus()) && b.getQueuedForAssignmentAt() != null
                        && b.getQueuedForAssignmentAt().isBefore(cutoff))
                .sorted(Comparator.comparing(Booking::getQueuedForAssignmentAt))
                .limit(pageable.getPageSize())
                .toList();
    }

    @Override
    public Optional<Booking> findByReference(String reference) {
        return store.values().stream().filter(b -> b.getReference().equals(reference)).findFirst();
    }

    @Override
    public boolean existsByReference(String reference) {
        return findByReference(reference).isPresent();
    }

    @Override
    public List<Booking> findByProviderIdAndStatusInOrderByScheduledAtAsc(
            UUID providerId, Collection<BookingStatus> statuses) {
        return store.values().stream()
                .filter(b -> providerId.equals(b.getProviderId()) && statuses.contains(b.getStatus()))
                .sorted(Comparator.comparing(Booking::getScheduledAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public Page<Booking> findByCustomerIdOrderByCreatedAtDescIdDesc(UUID customerId, Pageable pageable) {
        List<Booking> matching = store.values().stream()
                .filter(b -> b.getCustomerId().equals(customerId))
                .sorted(NEWEST_FIRST)
                .toList();
        int from = (int) Math.min(pageable.getOffset(), matching.size());
        int to = Math.min(from + pageable.getPageSize(), matching.size());
        return new PageImpl<>(matching.subList(from, to), pageable, matching.size());
    }

    @Override
    public List<Booking> findByReferenceContainingIgnoreCaseAndStatusInOrderByCreatedAtDescIdDesc(
            String fragment, Collection<BookingStatus> statuses, Pageable pageable) {
        String needle = fragment.toLowerCase(Locale.ROOT);
        return store.values().stream()
                .filter(b -> b.getReference().toLowerCase(Locale.ROOT).contains(needle)
                        && statuses.contains(b.getStatus()))
                .sorted(NEWEST_FIRST)
                .skip(pageable.getOffset())
                .limit(pageable.getPageSize())
                .toList();
    }

    @Override
    public Optional<Booking> findFirstByCustomerIdAndAddressIdAndStatusIn(
            UUID customerId, UUID addressId, Collection<BookingStatus> statuses) {
        return store.values().stream()
                .filter(b -> b.getCustomerId().equals(customerId)
                        && addressId.equals(b.getAddressId())
                        && statuses.contains(b.getStatus()))
                .findFirst();
    }

    @Override
    public <S extends Booking> S save(S entity) {
        store.put(entity.getId(), entity);
        return entity;
    }

    @Override
    public <S extends Booking> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>();
        entities.forEach(e -> saved.add(save(e)));
        return saved;
    }

    @Override
    public Optional<Booking> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public boolean existsById(UUID id) {
        return store.containsKey(id);
    }

    @Override
    public List<Booking> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public long count() {
        return store.size();
    }

    // ---- Unused JpaRepository surface ----

    @Override
    public List<Booking> findAll(Sort sort) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Booking> findAllById(Iterable<UUID> ids) {
        List<Booking> result = new ArrayList<>();
        ids.forEach(id -> findById(id).ifPresent(result::add));
        return result;
    }

    @Override
    public void deleteById(UUID id) {
        store.remove(id);
    }

    @Override
    public void delete(Booking entity) {
        store.remove(entity.getId());
    }

    @Override
    public void deleteAllById(Iterable<? extends UUID> ids) {
        ids.forEach(store::remove);
    }

    @Override
    public void deleteAll(Iterable<? extends Booking> entities) {
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
    public <S extends Booking> S saveAndFlush(S entity) {
        return save(entity);
    }

    @Override
    public <S extends Booking> List<S> saveAllAndFlush(Iterable<S> entities) {
        return saveAll(entities);
    }

    @Override
    public void deleteAllInBatch(Iterable<Booking> entities) {
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
    public Booking getOne(UUID id) {
        return getReferenceById(id);
    }

    @Override
    public Booking getById(UUID id) {
        return getReferenceById(id);
    }

    @Override
    public Booking getReferenceById(UUID id) {
        return findById(id).orElseThrow();
    }

    @Override
    public <S extends Booking> Optional<S> findOne(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Booking> List<S> findAll(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Booking> List<S> findAll(Example<S> example, Sort sort) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Booking> Page<S> findAll(Example<S> example, Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Booking> long count(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Booking> boolean exists(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Page<Booking> findAll(Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Booking, R> R findBy(Example<S> example,
            java.util.function.Function<FetchableFluentQuery<S>, R> queryFunction) {
        throw new UnsupportedOperationException();
    }
}
