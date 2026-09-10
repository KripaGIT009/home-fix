package com.homefix.outbox.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import com.homefix.shared.outbox.OutboxEventEntity;
import com.homefix.shared.outbox.OutboxEventRepository;
import com.homefix.shared.outbox.OutboxEventStatus;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Minimal in-memory {@link OutboxEventRepository} for pure unit tests — no Spring, JPA, or
 * database. Implements only the methods the relay and poller actually call; the remaining
 * {@link OutboxEventRepository}/{@code JpaRepository} surface throws to make accidental use
 * obvious.
 */
public class InMemoryOutboxEventRepository implements OutboxEventRepository {

    private final Map<UUID, OutboxEventEntity> store = new LinkedHashMap<>();

    public InMemoryOutboxEventRepository add(OutboxEventEntity event) {
        store.put(event.getId(), event);
        return this;
    }

    @Override
    public List<OutboxEventEntity> findByStatusOrderByCreatedAtAsc(OutboxEventStatus status,
                                                                   Pageable pageable) {
        List<OutboxEventEntity> matches = store.values().stream()
                .filter(e -> e.getStatus() == status)
                .sorted(Comparator.comparing(OutboxEventEntity::getCreatedAt))
                .toList();
        int from = (int) Math.min(pageable.getOffset(), matches.size());
        int to = Math.min(from + pageable.getPageSize(), matches.size());
        return new ArrayList<>(matches.subList(from, to));
    }

    @Override
    public long countByStatus(OutboxEventStatus status) {
        return store.values().stream().filter(e -> e.getStatus() == status).count();
    }

    @Override
    public <S extends OutboxEventEntity> S save(S entity) {
        store.put(entity.getId(), entity);
        return entity;
    }

    @Override
    public Optional<OutboxEventEntity> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public long count() {
        return store.size();
    }

    // ----- Unused JpaRepository surface -----

    @Override
    public <S extends OutboxEventEntity> List<S> saveAll(Iterable<S> entities) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean existsById(UUID id) {
        return store.containsKey(id);
    }

    @Override
    public List<OutboxEventEntity> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public List<OutboxEventEntity> findAllById(Iterable<UUID> ids) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteById(UUID id) {
        store.remove(id);
    }

    @Override
    public void delete(OutboxEventEntity entity) {
        store.remove(entity.getId());
    }

    @Override
    public void deleteAllById(Iterable<? extends UUID> ids) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAll(Iterable<? extends OutboxEventEntity> entities) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAll() {
        store.clear();
    }

    @Override
    public List<OutboxEventEntity> findAll(Sort sort) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Page<OutboxEventEntity> findAll(Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void flush() {
        // no-op
    }

    @Override
    public <S extends OutboxEventEntity> S saveAndFlush(S entity) {
        return save(entity);
    }

    @Override
    public <S extends OutboxEventEntity> List<S> saveAllAndFlush(Iterable<S> entities) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllInBatch(Iterable<OutboxEventEntity> entities) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllByIdInBatch(Iterable<UUID> ids) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllInBatch() {
        store.clear();
    }

    @Override
    public OutboxEventEntity getOne(UUID id) {
        return store.get(id);
    }

    @Override
    public OutboxEventEntity getById(UUID id) {
        return store.get(id);
    }

    @Override
    public OutboxEventEntity getReferenceById(UUID id) {
        return store.get(id);
    }

    @Override
    public <S extends OutboxEventEntity> Optional<S> findOne(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends OutboxEventEntity> List<S> findAll(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends OutboxEventEntity> List<S> findAll(Example<S> example, Sort sort) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends OutboxEventEntity> Page<S> findAll(Example<S> example, Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends OutboxEventEntity> long count(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends OutboxEventEntity> boolean exists(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends OutboxEventEntity, R> R findBy(Example<S> example,
            Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
        throw new UnsupportedOperationException();
    }
}
