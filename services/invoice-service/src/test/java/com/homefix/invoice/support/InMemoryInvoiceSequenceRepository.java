package com.homefix.invoice.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.homefix.invoice.domain.InvoiceSequence;
import com.homefix.invoice.domain.InvoiceSequenceRepository;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;

/**
 * Minimal in-memory {@link InvoiceSequenceRepository} for unit tests. {@link #lockByPeriod(String)}
 * returns the live stored instance (mutations are visible), mirroring the pessimistic-lock read the
 * real repository performs so the generator's claim + increment behaves identically without a DB.
 * Only the methods exercised by the generator are implemented; the rest throw.
 */
public class InMemoryInvoiceSequenceRepository implements InvoiceSequenceRepository {

    private final Map<String, InvoiceSequence> byPeriod = new ConcurrentHashMap<>();

    @Override
    public Optional<InvoiceSequence> lockByPeriod(String period) {
        return Optional.ofNullable(byPeriod.get(period));
    }

    @Override
    public <S extends InvoiceSequence> S save(S entity) {
        byPeriod.put(entity.getPeriod(), entity);
        return entity;
    }

    @Override
    public <S extends InvoiceSequence> S saveAndFlush(S entity) {
        return save(entity);
    }

    @Override
    public Optional<InvoiceSequence> findById(String period) {
        return Optional.ofNullable(byPeriod.get(period));
    }

    @Override
    public boolean existsById(String period) {
        return byPeriod.containsKey(period);
    }

    @Override
    public List<InvoiceSequence> findAll() {
        return new ArrayList<>(byPeriod.values());
    }

    @Override
    public long count() {
        return byPeriod.size();
    }

    // ---- Unused JpaRepository surface ----------------------------------------------------------

    @Override
    public void flush() {
    }

    @Override
    public <S extends InvoiceSequence> List<S> saveAllAndFlush(Iterable<S> entities) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllInBatch(Iterable<InvoiceSequence> entities) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllByIdInBatch(Iterable<String> strings) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllInBatch() {
        byPeriod.clear();
    }

    @Override
    public InvoiceSequence getOne(String s) {
        throw new UnsupportedOperationException();
    }

    @Override
    public InvoiceSequence getById(String s) {
        throw new UnsupportedOperationException();
    }

    @Override
    public InvoiceSequence getReferenceById(String s) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends InvoiceSequence> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>();
        entities.forEach(e -> saved.add(save(e)));
        return saved;
    }

    @Override
    public List<InvoiceSequence> findAllById(Iterable<String> strings) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteById(String s) {
        byPeriod.remove(s);
    }

    @Override
    public void delete(InvoiceSequence entity) {
        byPeriod.remove(entity.getPeriod());
    }

    @Override
    public void deleteAllById(Iterable<? extends String> strings) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAll(Iterable<? extends InvoiceSequence> entities) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAll() {
        byPeriod.clear();
    }

    @Override
    public List<InvoiceSequence> findAll(Sort sort) {
        throw new UnsupportedOperationException();
    }

    @Override
    public org.springframework.data.domain.Page<InvoiceSequence> findAll(org.springframework.data.domain.Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends InvoiceSequence> Optional<S> findOne(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends InvoiceSequence> List<S> findAll(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends InvoiceSequence> List<S> findAll(Example<S> example, Sort sort) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends InvoiceSequence> org.springframework.data.domain.Page<S> findAll(Example<S> example, org.springframework.data.domain.Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends InvoiceSequence> long count(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends InvoiceSequence> boolean exists(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends InvoiceSequence, R> R findBy(Example<S> example,
            java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
        throw new UnsupportedOperationException();
    }
}
