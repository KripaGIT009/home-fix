package com.homefix.provider.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;

import com.homefix.provider.domain.ProviderProfile;
import com.homefix.provider.domain.ProviderProfileRepository;

/**
 * Minimal in-memory {@link ProviderProfileRepository} for service unit tests, so wallet-balance
 * mutations persist across calls without a database or Spring context. Only the methods used by
 * {@code ProviderService} are implemented; the rest throw {@link UnsupportedOperationException}.
 */
public class InMemoryProviderProfileRepository implements ProviderProfileRepository {

    private final Map<UUID, ProviderProfile> store = new HashMap<>();

    @Override
    public Optional<ProviderProfile> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<ProviderProfile> findByUserId(UUID userId) {
        return store.values().stream().filter(p -> userId.equals(p.getUserId())).findFirst();
    }

    @Override
    public <S extends ProviderProfile> S save(S entity) {
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
    public <S extends ProviderProfile> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>();
        entities.forEach(e -> saved.add(save(e)));
        return saved;
    }

    @Override
    public List<ProviderProfile> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public List<ProviderProfile> findAll(Sort sort) {
        return findAll();
    }

    @Override
    public List<ProviderProfile> findAllById(Iterable<UUID> ids) {
        List<ProviderProfile> result = new ArrayList<>();
        ids.forEach(id -> findById(id).ifPresent(result::add));
        return result;
    }

    @Override
    public void deleteById(UUID id) {
        store.remove(id);
    }

    @Override
    public void delete(ProviderProfile entity) {
        store.remove(entity.getId());
    }

    @Override
    public void deleteAllById(Iterable<? extends UUID> ids) {
        ids.forEach(store::remove);
    }

    @Override
    public void deleteAll(Iterable<? extends ProviderProfile> entities) {
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
    public <S extends ProviderProfile> S saveAndFlush(S entity) {
        return save(entity);
    }

    @Override
    public <S extends ProviderProfile> List<S> saveAllAndFlush(Iterable<S> entities) {
        return saveAll(entities);
    }

    @Override
    public void deleteAllInBatch(Iterable<ProviderProfile> entities) {
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
    public ProviderProfile getOne(UUID id) {
        return getReferenceById(id);
    }

    @Override
    public ProviderProfile getById(UUID id) {
        return getReferenceById(id);
    }

    @Override
    public ProviderProfile getReferenceById(UUID id) {
        return findById(id).orElseThrow();
    }

    @Override
    public <S extends ProviderProfile> Optional<S> findOne(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ProviderProfile> List<S> findAll(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ProviderProfile> List<S> findAll(Example<S> example, Sort sort) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ProviderProfile> org.springframework.data.domain.Page<S> findAll(
            Example<S> example, org.springframework.data.domain.Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ProviderProfile> long count(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ProviderProfile> boolean exists(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public org.springframework.data.domain.Page<ProviderProfile> findAll(
            org.springframework.data.domain.Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ProviderProfile, R> R findBy(Example<S> example,
            java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
        throw new UnsupportedOperationException();
    }
}
