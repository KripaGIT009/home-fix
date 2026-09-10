package com.homefix.rating.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.homefix.rating.audit.AuditLogEntry;
import com.homefix.rating.audit.AuditLogRepository;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;

/**
 * Minimal in-memory {@link AuditLogRepository} for unit tests. Records saved entries so removal
 * audit assertions (Requirement 15.9) can inspect actor and reason.
 */
public class InMemoryAuditLogRepository implements AuditLogRepository {

    private final Map<UUID, AuditLogEntry> byId = new ConcurrentHashMap<>();

    @Override
    public <S extends AuditLogEntry> S save(S entity) {
        byId.put(entity.getId(), entity);
        return entity;
    }

    @Override
    public List<AuditLogEntry> findAll() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public Optional<AuditLogEntry> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
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
    public <S extends AuditLogEntry> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>();
        entities.forEach(e -> saved.add(save(e)));
        return saved;
    }

    // ---- Unused JpaRepository surface ----------------------------------------------------------

    @Override public void flush() { }
    @Override public <S extends AuditLogEntry> S saveAndFlush(S entity) { return save(entity); }
    @Override public <S extends AuditLogEntry> List<S> saveAllAndFlush(Iterable<S> entities) { throw unsupported(); }
    @Override public void deleteAllInBatch(Iterable<AuditLogEntry> entities) { throw unsupported(); }
    @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) { throw unsupported(); }
    @Override public void deleteAllInBatch() { byId.clear(); }
    @Override public AuditLogEntry getOne(UUID id) { throw unsupported(); }
    @Override public AuditLogEntry getById(UUID id) { throw unsupported(); }
    @Override public AuditLogEntry getReferenceById(UUID id) { throw unsupported(); }
    @Override public List<AuditLogEntry> findAllById(Iterable<UUID> ids) { throw unsupported(); }
    @Override public void deleteById(UUID id) { byId.remove(id); }
    @Override public void delete(AuditLogEntry entity) { byId.remove(entity.getId()); }
    @Override public void deleteAllById(Iterable<? extends UUID> ids) { throw unsupported(); }
    @Override public void deleteAll(Iterable<? extends AuditLogEntry> entities) { throw unsupported(); }
    @Override public List<AuditLogEntry> findAll(Sort sort) { throw unsupported(); }
    @Override public Page<AuditLogEntry> findAll(Pageable pageable) { throw unsupported(); }
    @Override public <S extends AuditLogEntry> Optional<S> findOne(Example<S> example) { throw unsupported(); }
    @Override public <S extends AuditLogEntry> List<S> findAll(Example<S> example) { throw unsupported(); }
    @Override public <S extends AuditLogEntry> List<S> findAll(Example<S> example, Sort sort) { throw unsupported(); }
    @Override public <S extends AuditLogEntry> Page<S> findAll(Example<S> example, Pageable pageable) { throw unsupported(); }
    @Override public <S extends AuditLogEntry> long count(Example<S> example) { throw unsupported(); }
    @Override public <S extends AuditLogEntry> boolean exists(Example<S> example) { throw unsupported(); }
    @Override public <S extends AuditLogEntry, R> R findBy(Example<S> example,
            Function<FetchableFluentQuery<S>, R> queryFunction) { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not needed for unit tests");
    }
}
