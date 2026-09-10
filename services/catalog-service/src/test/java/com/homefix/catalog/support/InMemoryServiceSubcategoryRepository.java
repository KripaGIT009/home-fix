package com.homefix.catalog.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;

import com.homefix.catalog.domain.ServiceSubcategory;
import com.homefix.catalog.domain.ServiceSubcategoryRepository;

/**
 * Minimal in-memory {@link ServiceSubcategoryRepository} for service unit tests.
 */
public class InMemoryServiceSubcategoryRepository implements ServiceSubcategoryRepository {

    private final Map<UUID, ServiceSubcategory> store = new LinkedHashMap<>();

    @Override
    public List<ServiceSubcategory> findByCategoryId(UUID categoryId) {
        return store.values().stream()
                .filter(s -> categoryId.equals(s.getCategoryId()))
                .toList();
    }

    @Override
    public List<ServiceSubcategory> findByCategoryIdAndActiveTrue(UUID categoryId) {
        return store.values().stream()
                .filter(s -> categoryId.equals(s.getCategoryId()) && s.isActive())
                .toList();
    }

    @Override
    public Optional<ServiceSubcategory> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public <S extends ServiceSubcategory> S save(S entity) {
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
    public void delete(ServiceSubcategory entity) {
        store.remove(entity.getId());
    }

    @Override
    public void deleteAll(Iterable<? extends ServiceSubcategory> entities) {
        entities.forEach(this::delete);
    }

    @Override
    public List<ServiceSubcategory> findAll() {
        return new ArrayList<>(store.values());
    }

    // ---- Unused JpaRepository surface ----

    @Override
    public <S extends ServiceSubcategory> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>();
        entities.forEach(e -> saved.add(save(e)));
        return saved;
    }

    @Override
    public List<ServiceSubcategory> findAll(Sort sort) {
        return findAll();
    }

    @Override
    public List<ServiceSubcategory> findAllById(Iterable<UUID> ids) {
        List<ServiceSubcategory> result = new ArrayList<>();
        ids.forEach(id -> findById(id).ifPresent(result::add));
        return result;
    }

    @Override
    public void deleteAllById(Iterable<? extends UUID> ids) {
        ids.forEach(store::remove);
    }

    @Override
    public void deleteAll() {
        store.clear();
    }

    @Override
    public void flush() {
    }

    @Override
    public <S extends ServiceSubcategory> S saveAndFlush(S entity) {
        return save(entity);
    }

    @Override
    public <S extends ServiceSubcategory> List<S> saveAllAndFlush(Iterable<S> entities) {
        return saveAll(entities);
    }

    @Override
    public void deleteAllInBatch(Iterable<ServiceSubcategory> entities) {
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
    public ServiceSubcategory getOne(UUID id) {
        return getReferenceById(id);
    }

    @Override
    public ServiceSubcategory getById(UUID id) {
        return getReferenceById(id);
    }

    @Override
    public ServiceSubcategory getReferenceById(UUID id) {
        return findById(id).orElseThrow();
    }

    @Override
    public <S extends ServiceSubcategory> Optional<S> findOne(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ServiceSubcategory> List<S> findAll(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ServiceSubcategory> List<S> findAll(Example<S> example, Sort sort) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ServiceSubcategory> org.springframework.data.domain.Page<S> findAll(
            Example<S> example, org.springframework.data.domain.Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ServiceSubcategory> long count(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ServiceSubcategory> boolean exists(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public org.springframework.data.domain.Page<ServiceSubcategory> findAll(
            org.springframework.data.domain.Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends ServiceSubcategory, R> R findBy(Example<S> example,
            java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
        throw new UnsupportedOperationException();
    }
}
