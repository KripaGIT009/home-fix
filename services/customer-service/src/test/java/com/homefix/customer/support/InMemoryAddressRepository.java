package com.homefix.customer.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;

import com.homefix.customer.domain.Address;
import com.homefix.customer.domain.AddressRepository;

/**
 * Hand-rolled in-memory {@link AddressRepository} for fast unit tests. Only the methods
 * exercised by {@code CustomerProfileService} are meaningfully implemented; the rest throw
 * {@link UnsupportedOperationException} to surface accidental use.
 */
public class InMemoryAddressRepository implements AddressRepository {

    private final Map<UUID, Address> store = new LinkedHashMap<>();

    @Override
    public List<Address> findByCustomerIdAndIsActiveTrue(UUID customerId) {
        return store.values().stream()
                .filter(a -> a.getCustomerId().equals(customerId) && a.isActive())
                .toList();
    }

    @Override
    public long countByCustomerIdAndIsActiveTrue(UUID customerId) {
        return findByCustomerIdAndIsActiveTrue(customerId).size();
    }

    @Override
    public Optional<Address> findByIdAndCustomerId(UUID id, UUID customerId) {
        return Optional.ofNullable(store.get(id))
                .filter(a -> a.getCustomerId().equals(customerId));
    }

    @Override
    public List<Address> findByCustomerIdAndIsActiveTrueOrderByCreatedAtDesc(UUID customerId) {
        List<Address> list = new ArrayList<>(findByCustomerIdAndIsActiveTrue(customerId));
        // Addresses added in quick succession can share a createdAt. Reverse insertion order first
        // so the stable sort breaks those ties newest-first, as the real insert order would.
        Collections.reverse(list);
        list.sort(Comparator.comparing(Address::getCreatedAt).reversed());
        return list;
    }

    @Override
    public <S extends Address> S save(S entity) {
        store.put(entity.getId(), entity);
        return entity;
    }

    @Override
    public Optional<Address> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public void delete(Address entity) {
        store.remove(entity.getId());
    }

    @Override
    public void deleteAll(Iterable<? extends Address> entities) {
        entities.forEach(e -> store.remove(e.getId()));
    }

    @Override
    public long count() {
        return store.size();
    }

    // ----- Unused JpaRepository surface -----

    @Override public <S extends Address> List<S> saveAll(Iterable<S> entities) {
        List<S> out = new ArrayList<>();
        entities.forEach(e -> { save(e); out.add(e); });
        return out;
    }
    @Override public List<Address> findAll() { return new ArrayList<>(store.values()); }
    @Override public List<Address> findAllById(Iterable<UUID> ids) { throw unsupported(); }
    @Override public boolean existsById(UUID id) { return store.containsKey(id); }
    @Override public void deleteById(UUID id) { store.remove(id); }
    @Override public void deleteAllById(Iterable<? extends UUID> ids) { throw unsupported(); }
    @Override public void deleteAll() { store.clear(); }
    @Override public void flush() { }
    @Override public <S extends Address> S saveAndFlush(S entity) { return save(entity); }
    @Override public <S extends Address> List<S> saveAllAndFlush(Iterable<S> entities) { return saveAll(entities); }
    @Override public void deleteAllInBatch(Iterable<Address> entities) { throw unsupported(); }
    @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) { throw unsupported(); }
    @Override public void deleteAllInBatch() { store.clear(); }
    @Override public Address getOne(UUID id) { throw unsupported(); }
    @Override public Address getById(UUID id) { throw unsupported(); }
    @Override public Address getReferenceById(UUID id) { throw unsupported(); }
    @Override public List<Address> findAll(Sort sort) { throw unsupported(); }
    @Override public org.springframework.data.domain.Page<Address> findAll(org.springframework.data.domain.Pageable pageable) { throw unsupported(); }
    @Override public <S extends Address> Optional<S> findOne(Example<S> example) { throw unsupported(); }
    @Override public <S extends Address> List<S> findAll(Example<S> example) { throw unsupported(); }
    @Override public <S extends Address> List<S> findAll(Example<S> example, Sort sort) { throw unsupported(); }
    @Override public <S extends Address> org.springframework.data.domain.Page<S> findAll(Example<S> example, org.springframework.data.domain.Pageable pageable) { throw unsupported(); }
    @Override public <S extends Address> long count(Example<S> example) { throw unsupported(); }
    @Override public <S extends Address> boolean exists(Example<S> example) { throw unsupported(); }
    @Override public <S extends Address, R> R findBy(Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not needed for unit tests");
    }
}
