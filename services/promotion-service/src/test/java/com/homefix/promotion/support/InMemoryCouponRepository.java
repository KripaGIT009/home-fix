package com.homefix.promotion.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;

import com.homefix.promotion.domain.Coupon;
import com.homefix.promotion.domain.CouponRepository;

/**
 * Minimal in-memory {@link CouponRepository} for unit tests. Only the query methods exercised by
 * the service layer are implemented; the remaining {@code JpaRepository} surface throws.
 *
 * <p>Codes are matched on their normalised (upper-case) form, mirroring how the entity stores them
 * so case-insensitive lookup and duplicate detection behave as in production (Requirement 21.1).
 */
public class InMemoryCouponRepository implements CouponRepository {

    private final Map<UUID, Coupon> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<Coupon> findByCode(String code) {
        String normalised = Coupon.normaliseCode(code);
        return byId.values().stream()
                .filter(c -> c.getCode().equals(normalised))
                .findFirst();
    }

    @Override
    public boolean existsByCode(String code) {
        String normalised = Coupon.normaliseCode(code);
        return byId.values().stream().anyMatch(c -> c.getCode().equals(normalised));
    }

    @Override
    public <S extends Coupon> S save(S entity) {
        byId.put(entity.getId(), entity);
        return entity;
    }

    @Override
    public Optional<Coupon> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Coupon> findAll() {
        return new ArrayList<>(byId.values());
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
    public void delete(Coupon entity) {
        byId.remove(entity.getId());
    }

    @Override
    public void deleteAll() {
        byId.clear();
    }

    @Override
    public <S extends Coupon> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>();
        entities.forEach(e -> saved.add(save(e)));
        return saved;
    }

    // ---- Unused JpaRepository surface ----------------------------------------------------------

    @Override public void flush() { }
    @Override public <S extends Coupon> S saveAndFlush(S entity) { return save(entity); }
    @Override public <S extends Coupon> List<S> saveAllAndFlush(Iterable<S> entities) { throw unsupported(); }
    @Override public void deleteAllInBatch(Iterable<Coupon> entities) { throw unsupported(); }
    @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) { throw unsupported(); }
    @Override public void deleteAllInBatch() { byId.clear(); }
    @Override public Coupon getOne(UUID id) { throw unsupported(); }
    @Override public Coupon getById(UUID id) { throw unsupported(); }
    @Override public Coupon getReferenceById(UUID id) { throw unsupported(); }
    @Override public List<Coupon> findAllById(Iterable<UUID> ids) { throw unsupported(); }
    @Override public void deleteAllById(Iterable<? extends UUID> ids) { throw unsupported(); }
    @Override public void deleteAll(Iterable<? extends Coupon> entities) { throw unsupported(); }
    @Override public List<Coupon> findAll(Sort sort) { throw unsupported(); }
    @Override public Page<Coupon> findAll(Pageable pageable) { throw unsupported(); }
    @Override public <S extends Coupon> Optional<S> findOne(Example<S> example) { throw unsupported(); }
    @Override public <S extends Coupon> List<S> findAll(Example<S> example) { throw unsupported(); }
    @Override public <S extends Coupon> List<S> findAll(Example<S> example, Sort sort) { throw unsupported(); }
    @Override public <S extends Coupon> Page<S> findAll(Example<S> example, Pageable pageable) { throw unsupported(); }
    @Override public <S extends Coupon> long count(Example<S> example) { throw unsupported(); }
    @Override public <S extends Coupon> boolean exists(Example<S> example) { throw unsupported(); }
    @Override public <S extends Coupon, R> R findBy(Example<S> example,
            Function<FetchableFluentQuery<S>, R> queryFunction) { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not needed for unit tests");
    }
}
