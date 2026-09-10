package com.homefix.rating.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.homefix.rating.domain.ReviewPrompt;
import com.homefix.rating.domain.ReviewPromptRepository;
import com.homefix.rating.domain.ReviewerRole;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;

/**
 * Minimal in-memory {@link ReviewPromptRepository} for unit tests.
 */
public class InMemoryReviewPromptRepository implements ReviewPromptRepository {

    private final Map<UUID, ReviewPrompt> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<ReviewPrompt> findByBookingIdAndReviewerRole(UUID bookingId, ReviewerRole role) {
        return byId.values().stream()
                .filter(p -> p.getBookingId().equals(bookingId) && p.getReviewerRole() == role)
                .findFirst();
    }

    @Override
    public boolean existsByPaymentId(UUID paymentId) {
        return byId.values().stream().anyMatch(p -> p.getPaymentId().equals(paymentId));
    }

    @Override
    public <S extends ReviewPrompt> S save(S entity) {
        byId.put(entity.getId(), entity);
        return entity;
    }

    @Override
    public Optional<ReviewPrompt> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<ReviewPrompt> findAll() {
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
    public void deleteAll() {
        byId.clear();
    }

    @Override
    public <S extends ReviewPrompt> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>();
        entities.forEach(e -> saved.add(save(e)));
        return saved;
    }

    // ---- Unused JpaRepository surface ----------------------------------------------------------

    @Override public void flush() { }
    @Override public <S extends ReviewPrompt> S saveAndFlush(S entity) { return save(entity); }
    @Override public <S extends ReviewPrompt> List<S> saveAllAndFlush(Iterable<S> entities) { throw unsupported(); }
    @Override public void deleteAllInBatch(Iterable<ReviewPrompt> entities) { throw unsupported(); }
    @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) { throw unsupported(); }
    @Override public void deleteAllInBatch() { byId.clear(); }
    @Override public ReviewPrompt getOne(UUID id) { throw unsupported(); }
    @Override public ReviewPrompt getById(UUID id) { throw unsupported(); }
    @Override public ReviewPrompt getReferenceById(UUID id) { throw unsupported(); }
    @Override public List<ReviewPrompt> findAllById(Iterable<UUID> ids) { throw unsupported(); }
    @Override public void deleteById(UUID id) { byId.remove(id); }
    @Override public void delete(ReviewPrompt entity) { byId.remove(entity.getId()); }
    @Override public void deleteAllById(Iterable<? extends UUID> ids) { throw unsupported(); }
    @Override public void deleteAll(Iterable<? extends ReviewPrompt> entities) { throw unsupported(); }
    @Override public List<ReviewPrompt> findAll(Sort sort) { throw unsupported(); }
    @Override public Page<ReviewPrompt> findAll(Pageable pageable) { throw unsupported(); }
    @Override public <S extends ReviewPrompt> Optional<S> findOne(Example<S> example) { throw unsupported(); }
    @Override public <S extends ReviewPrompt> List<S> findAll(Example<S> example) { throw unsupported(); }
    @Override public <S extends ReviewPrompt> List<S> findAll(Example<S> example, Sort sort) { throw unsupported(); }
    @Override public <S extends ReviewPrompt> Page<S> findAll(Example<S> example, Pageable pageable) { throw unsupported(); }
    @Override public <S extends ReviewPrompt> long count(Example<S> example) { throw unsupported(); }
    @Override public <S extends ReviewPrompt> boolean exists(Example<S> example) { throw unsupported(); }
    @Override public <S extends ReviewPrompt, R> R findBy(Example<S> example,
            Function<FetchableFluentQuery<S>, R> queryFunction) { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not needed for unit tests");
    }
}
