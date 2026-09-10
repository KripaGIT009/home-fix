package com.homefix.rating.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.homefix.rating.domain.Review;
import com.homefix.rating.domain.ReviewRepository;
import com.homefix.rating.domain.ReviewerRole;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;

/**
 * Minimal in-memory {@link ReviewRepository} for unit tests. Only the query methods used by the
 * service layer are implemented; the remaining {@code JpaRepository} surface throws.
 */
public class InMemoryReviewRepository implements ReviewRepository {

    private final Map<UUID, Review> byId = new ConcurrentHashMap<>();

    @Override
    public List<Review> findByRevieweeIdAndReviewerRole(UUID revieweeId, ReviewerRole reviewerRole) {
        return byId.values().stream()
                .filter(r -> r.getRevieweeId().equals(revieweeId))
                .filter(r -> r.getReviewerRole() == reviewerRole)
                .toList();
    }

    @Override
    public List<Review> findBySourceIpAndSubmittedAtGreaterThanEqual(String sourceIp, Instant since) {
        return byId.values().stream()
                .filter(r -> sourceIp != null && sourceIp.equals(r.getSourceIp()))
                .filter(r -> !r.getSubmittedAt().isBefore(since))
                .toList();
    }

    @Override
    public boolean existsByBookingIdAndReviewerId(UUID bookingId, UUID reviewerId) {
        return byId.values().stream()
                .anyMatch(r -> r.getBookingId().equals(bookingId) && r.getReviewerId().equals(reviewerId));
    }

    @Override
    public <S extends Review> S save(S entity) {
        byId.put(entity.getId(), entity);
        return entity;
    }

    @Override
    public Optional<Review> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Review> findAll() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public long count() {
        return byId.size();
    }

    @Override
    public boolean existsById(UUID id) {
        return byId.containsKey(id);
    }

    @Override
    public void deleteById(UUID id) {
        byId.remove(id);
    }

    @Override
    public void delete(Review entity) {
        byId.remove(entity.getId());
    }

    @Override
    public void deleteAll() {
        byId.clear();
    }

    @Override
    public <S extends Review> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>();
        entities.forEach(e -> saved.add(save(e)));
        return saved;
    }

    // ---- Unused JpaRepository surface ----------------------------------------------------------

    @Override public void flush() { }
    @Override public <S extends Review> S saveAndFlush(S entity) { return save(entity); }
    @Override public <S extends Review> List<S> saveAllAndFlush(Iterable<S> entities) { throw unsupported(); }
    @Override public void deleteAllInBatch(Iterable<Review> entities) { throw unsupported(); }
    @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) { throw unsupported(); }
    @Override public void deleteAllInBatch() { byId.clear(); }
    @Override public Review getOne(UUID id) { throw unsupported(); }
    @Override public Review getById(UUID id) { throw unsupported(); }
    @Override public Review getReferenceById(UUID id) { throw unsupported(); }
    @Override public List<Review> findAllById(Iterable<UUID> ids) { throw unsupported(); }
    @Override public void deleteAllById(Iterable<? extends UUID> ids) { throw unsupported(); }
    @Override public void deleteAll(Iterable<? extends Review> entities) { throw unsupported(); }
    @Override public List<Review> findAll(Sort sort) { throw unsupported(); }
    @Override public Page<Review> findAll(Pageable pageable) { throw unsupported(); }
    @Override public <S extends Review> Optional<S> findOne(Example<S> example) { throw unsupported(); }
    @Override public <S extends Review> List<S> findAll(Example<S> example) { throw unsupported(); }
    @Override public <S extends Review> List<S> findAll(Example<S> example, Sort sort) { throw unsupported(); }
    @Override public <S extends Review> Page<S> findAll(Example<S> example, Pageable pageable) { throw unsupported(); }
    @Override public <S extends Review> long count(Example<S> example) { throw unsupported(); }
    @Override public <S extends Review> boolean exists(Example<S> example) { throw unsupported(); }
    @Override public <S extends Review, R> R findBy(Example<S> example,
            Function<FetchableFluentQuery<S>, R> queryFunction) { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not needed for unit tests");
    }
}
