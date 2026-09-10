package com.homefix.complaint.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.homefix.complaint.domain.Complaint;
import com.homefix.complaint.domain.ComplaintRepository;
import com.homefix.complaint.domain.ComplaintStatus;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;

/**
 * Minimal in-memory {@link ComplaintRepository} for unit tests. Only the query methods used by the
 * service layer are implemented; the remaining {@code JpaRepository} surface throws.
 */
public class InMemoryComplaintRepository implements ComplaintRepository {

    private final Map<UUID, Complaint> byId = new ConcurrentHashMap<>();

    @Override
    public List<Complaint> findByStatusNotInAndEscalatedFalseAndSlaDeadlineLessThanEqual(
            List<ComplaintStatus> statuses, Instant asOf) {
        return byId.values().stream()
                .filter(c -> !statuses.contains(c.getStatus()))
                .filter(c -> !c.isEscalated())
                .filter(c -> !c.getSlaDeadline().isAfter(asOf))
                .toList();
    }

    @Override
    public List<Complaint> findAll() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public <S extends Complaint> S save(S entity) {
        byId.put(entity.getId(), entity);
        return entity;
    }

    @Override
    public Optional<Complaint> findById(UUID id) {
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
    public void deleteById(UUID id) {
        byId.remove(id);
    }

    @Override
    public void delete(Complaint entity) {
        byId.remove(entity.getId());
    }

    @Override
    public void deleteAll() {
        byId.clear();
    }

    @Override
    public <S extends Complaint> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>();
        entities.forEach(e -> saved.add(save(e)));
        return saved;
    }

    // ---- Unused JpaRepository surface ----------------------------------------------------------

    @Override public void flush() { }
    @Override public <S extends Complaint> S saveAndFlush(S entity) { return save(entity); }
    @Override public <S extends Complaint> List<S> saveAllAndFlush(Iterable<S> entities) { throw unsupported(); }
    @Override public void deleteAllInBatch(Iterable<Complaint> entities) { throw unsupported(); }
    @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) { throw unsupported(); }
    @Override public void deleteAllInBatch() { byId.clear(); }
    @Override public Complaint getOne(UUID id) { throw unsupported(); }
    @Override public Complaint getById(UUID id) { throw unsupported(); }
    @Override public Complaint getReferenceById(UUID id) { throw unsupported(); }
    @Override public List<Complaint> findAllById(Iterable<UUID> ids) { throw unsupported(); }
    @Override public void deleteAllById(Iterable<? extends UUID> ids) { throw unsupported(); }
    @Override public void deleteAll(Iterable<? extends Complaint> entities) { throw unsupported(); }
    @Override public List<Complaint> findAll(Sort sort) { throw unsupported(); }
    @Override public Page<Complaint> findAll(Pageable pageable) { throw unsupported(); }
    @Override public <S extends Complaint> Optional<S> findOne(Example<S> example) { throw unsupported(); }
    @Override public <S extends Complaint> List<S> findAll(Example<S> example) { throw unsupported(); }
    @Override public <S extends Complaint> List<S> findAll(Example<S> example, Sort sort) { throw unsupported(); }
    @Override public <S extends Complaint> Page<S> findAll(Example<S> example, Pageable pageable) { throw unsupported(); }
    @Override public <S extends Complaint> long count(Example<S> example) { throw unsupported(); }
    @Override public <S extends Complaint> boolean exists(Example<S> example) { throw unsupported(); }
    @Override public <S extends Complaint, R> R findBy(Example<S> example,
            Function<FetchableFluentQuery<S>, R> queryFunction) { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not needed for unit tests");
    }
}
