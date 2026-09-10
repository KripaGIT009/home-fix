package com.homefix.catalog.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;

import com.homefix.catalog.domain.ServiceCategory;
import com.homefix.catalog.domain.ServiceCategoryRepository;

/**
 * Minimal in-memory {@link ServiceCategoryRepository} for service unit tests, so state
 * mutations persist across calls without a database or Spring context. Only the methods used by
 * {@code CatalogService} are meaningfully implemented; the rest throw
 * {@link UnsupportedOperationException}.
 */
public class InMemoryServiceCategoryRepository implements ServiceCategoryRepository {

    private final Map<UUID, ServiceCategory> store = new LinkedHashMap<>();

    @Override
    public List<ServiceCategory> findByActiveTrueOrderByDisplayOrderAsc() {
        return store.values().stream()
                .filter(ServiceCategory::isActive)
                .sorted(Comparator.comparingInt(ServiceCategory::getDisplayOrder))
                .toList();
    }

    @Override
    public List<ServiceCategory> findAllByOrderByDisplayOrderAsc() {
        return store.values().stream()
                .sorted(Comparator.comparingInt(ServiceCategory::getDisplayOrder))
                .toList();
    }

    @Override
    public Optional<ServiceCategory> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public <S extends ServiceCategory> S save(S entity) {
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

    @Override
    public void deleteById(UUID id) {
        store.remove(id);
    }

    @Override
    public void delete(ServiceCategory entity) {
        store.remove(entity.getId());
    }

    @Override
    public List<ServiceCategory> findAll() {
        return new ArrayList<>(store.values());
    }

    // ---- Unused JpaRepository surface ----

    @Override
    public <S extends ServiceCategory> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>();
        entities.forEach(e -> saved.add(save(e)));
        return saved;
    }

    @Override
    public List<ServiceCategory> findAll(Sort sort) {
        return findAll();
    }

    @Override
    public List<ServiceCategory> findAllById(Iterable<UUID> ids) {
        List<ServiceCategory> result = new ArrayList<>();
        ids.forEach(id -> findById(id).ifPresent(result::add));
        return result;
    }

    @Override
    public void deleteAllById(Iterable<? extends UUID> ids) {
        ids.forEach(store::remove);
    }

    @Override
    public void deleteAll(Iterable<? extends ServiceCategory> entities) {
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
    public <S extends ServiceCategory> S saveAndFlush(S entity) {
        return save(entity);
    }

    @Override
    public <S extends ServiceCategory> List<S> saveAllAndFlush(Iterable<S> entities) {
        return saveAll(entities);
    }

    @Override
    public void deleteAllInBatch(Iterable<ServiceCategory> entities) {
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
    public ServiceCategory getOne(UUID id) {
        return getReferenceById(id);
    }

    @Override
    public ServiceCategory getById(UUID id) {
        return getReferenceById(id);
    }

    @Override
    public ServiceCategory getReferenceById(UUID id) {
        return findById(id).orElseThrow();
    }

    @Override
    public <S extends ServiceCategory> Optional<S> findOne(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ServiceCategory> List<S> findAll(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ServiceCategory> List<S> findAll(Example<S> example, Sort sort) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ServiceCategory> org.springframework.data.domain.Page<S> findAll(
            Example<S> example, org.springframework.data.domain.Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ServiceCategory> long count(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ServiceCategory> boolean exists(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public org.springframework.data.domain.Page<ServiceCategory> findAll(
            org.springframework.data.domain.Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ServiceCategory, R> R findBy(Example<S> example,
            java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
        throw new UnsupportedOperationException();
    }
}
