package com.homefix.verification.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;

import com.homefix.verification.domain.Verification;
import com.homefix.verification.domain.VerificationRepository;

/**
 * Minimal in-memory {@link VerificationRepository} for service unit tests, so status and audit
 * mutations persist across calls without a database or Spring context. Only the methods used by
 * {@code VerificationService} are implemented; the rest throw
 * {@link UnsupportedOperationException}.
 */
public class InMemoryVerificationRepository implements VerificationRepository {

    private final Map<UUID, Verification> store = new HashMap<>();

    @Override
    public Optional<Verification> findByProviderId(UUID providerId) {
        return store.values().stream().filter(v -> providerId.equals(v.getProviderId())).findFirst();
    }

    @Override
    public boolean existsByProviderId(UUID providerId) {
        return findByProviderId(providerId).isPresent();
    }

    @Override
    public Optional<Verification> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public <S extends Verification> S save(S entity) {
        store.put(entity.getId(), entity);
        return entity;
    }

    @Override
    public boolean existsById(UUID id) {
        return store.containsKey(id);
    }

    @Override
    public long count() {
        return store.size();
    }

    // ---- Unused JpaRepository surface ----

    @Override
    public <S extends Verification> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>();
        entities.forEach(e -> saved.add(save(e)));
        return saved;
    }

    @Override
    public List<Verification> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public List<Verification> findAll(Sort sort) {
        return findAll();
    }

    @Override
    public List<Verification> findAllById(Iterable<UUID> ids) {
        List<Verification> result = new ArrayList<>();
        ids.forEach(id -> findById(id).ifPresent(result::add));
        return result;
    }

    @Override
    public void deleteById(UUID id) {
        store.remove(id);
    }

    @Override
    public void delete(Verification entity) {
        store.remove(entity.getId());
    }

    @Override
    public void deleteAllById(Iterable<? extends UUID> ids) {
        ids.forEach(store::remove);
    }

    @Override
    public void deleteAll(Iterable<? extends Verification> entities) {
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
    public <S extends Verification> S saveAndFlush(S entity) {
        return save(entity);
    }

    @Override
    public <S extends Verification> List<S> saveAllAndFlush(Iterable<S> entities) {
        return saveAll(entities);
    }

    @Override
    public void deleteAllInBatch(Iterable<Verification> entities) {
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
    public Verification getOne(UUID id) {
        return getReferenceById(id);
    }

    @Override
    public Verification getById(UUID id) {
        return getReferenceById(id);
    }

    @Override
    public Verification getReferenceById(UUID id) {
        return findById(id).orElseThrow();
    }

    @Override
    public <S extends Verification> Optional<S> findOne(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Verification> List<S> findAll(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Verification> List<S> findAll(Example<S> example, Sort sort) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Verification> Page<S> findAll(Example<S> example, Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Verification> long count(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Verification> boolean exists(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Page<Verification> findAll(Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Verification, R> R findBy(Example<S> example,
            Function<FetchableFluentQuery<S>, R> queryFunction) {
        throw new UnsupportedOperationException();
    }
}
