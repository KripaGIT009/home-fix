package com.homefix.invoice.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.homefix.invoice.domain.Invoice;
import com.homefix.invoice.domain.InvoiceRepository;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Minimal in-memory {@link InvoiceRepository} for unit tests. Enforces the invoice-number and
 * payment-id uniqueness invariants so tests can assert them directly. Only the query methods used
 * by the service/query layers are implemented; the rest throw.
 */
public class InMemoryInvoiceRepository implements InvoiceRepository {

    private final Map<UUID, Invoice> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<Invoice> findByPaymentId(UUID paymentId) {
        return byId.values().stream().filter(i -> i.getPaymentId().equals(paymentId)).findFirst();
    }

    @Override
    public boolean existsByPaymentId(UUID paymentId) {
        return byId.values().stream().anyMatch(i -> i.getPaymentId().equals(paymentId));
    }

    @Override
    public List<Invoice> findByCustomerIdAndGeneratedAtGreaterThanEqualOrderByGeneratedAtDesc(
            UUID customerId, Instant since, Pageable pageable) {
        List<Invoice> all = byId.values().stream()
                .filter(i -> i.getCustomerId().equals(customerId))
                .filter(i -> !i.getGeneratedAt().isBefore(since))
                .sorted(Comparator.comparing(Invoice::getGeneratedAt).reversed())
                .toList();
        int from = (int) pageable.getOffset();
        if (from >= all.size()) {
            return List.of();
        }
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return all.subList(from, to);
    }

    @Override
    public List<Invoice> findByProviderIdAndGeneratedAtGreaterThanEqualAndGeneratedAtLessThanOrderByGeneratedAtDesc(
            UUID providerId, Instant from, Instant to) {
        return byId.values().stream()
                .filter(i -> i.getProviderId().equals(providerId))
                .filter(i -> !i.getGeneratedAt().isBefore(from) && i.getGeneratedAt().isBefore(to))
                .sorted(Comparator.comparing(Invoice::getGeneratedAt).reversed())
                .toList();
    }

    @Override
    public <S extends Invoice> S save(S entity) {
        // Enforce the unique constraints the real schema guarantees.
        for (Invoice existing : byId.values()) {
            if (existing.getId().equals(entity.getId())) {
                continue;
            }
            if (existing.getInvoiceNumber().equals(entity.getInvoiceNumber())) {
                throw new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate invoice_number " + entity.getInvoiceNumber());
            }
            if (existing.getPaymentId().equals(entity.getPaymentId())) {
                throw new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate payment_id " + entity.getPaymentId());
            }
        }
        byId.put(entity.getId(), entity);
        return entity;
    }

    @Override
    public List<Invoice> findAll() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public Optional<Invoice> findById(UUID uuid) {
        return Optional.ofNullable(byId.get(uuid));
    }

    @Override
    public boolean existsById(UUID uuid) {
        return byId.containsKey(uuid);
    }

    @Override
    public long count() {
        return byId.size();
    }

    // ---- Unused JpaRepository surface ----------------------------------------------------------

    @Override
    public void flush() {
    }

    @Override
    public <S extends Invoice> S saveAndFlush(S entity) {
        return save(entity);
    }

    @Override
    public <S extends Invoice> List<S> saveAllAndFlush(Iterable<S> entities) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllInBatch(Iterable<Invoice> entities) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllByIdInBatch(Iterable<UUID> uuids) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllInBatch() {
        byId.clear();
    }

    @Override
    public Invoice getOne(UUID uuid) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Invoice getById(UUID uuid) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Invoice getReferenceById(UUID uuid) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Invoice> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>();
        entities.forEach(e -> saved.add(save(e)));
        return saved;
    }

    @Override
    public List<Invoice> findAllById(Iterable<UUID> uuids) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteById(UUID uuid) {
        byId.remove(uuid);
    }

    @Override
    public void delete(Invoice entity) {
        byId.remove(entity.getId());
    }

    @Override
    public void deleteAllById(Iterable<? extends UUID> uuids) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAll(Iterable<? extends Invoice> entities) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAll() {
        byId.clear();
    }

    @Override
    public List<Invoice> findAll(Sort sort) {
        throw new UnsupportedOperationException();
    }

    @Override
    public org.springframework.data.domain.Page<Invoice> findAll(Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Invoice> Optional<S> findOne(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Invoice> List<S> findAll(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Invoice> List<S> findAll(Example<S> example, Sort sort) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Invoice> org.springframework.data.domain.Page<S> findAll(Example<S> example, Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Invoice> long count(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Invoice> boolean exists(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends Invoice, R> R findBy(Example<S> example,
            java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
        throw new UnsupportedOperationException();
    }
}
