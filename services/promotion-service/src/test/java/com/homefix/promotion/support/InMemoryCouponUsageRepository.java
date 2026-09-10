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

import com.homefix.promotion.domain.CouponUsage;
import com.homefix.promotion.domain.CouponUsageRepository;

/**
 * Minimal in-memory {@link CouponUsageRepository} for unit tests. Rows are keyed on the unique
 * {@code (couponId, userId)} pair, matching the production unique constraint (Requirement 21.3,
 * 21.5).
 */
public class InMemoryCouponUsageRepository implements CouponUsageRepository {

    private final Map<UUID, CouponUsage> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<CouponUsage> findByCouponIdAndUserId(UUID couponId, UUID userId) {
        return byId.values().stream()
                .filter(u -> u.getCouponId().equals(couponId) && u.getUserId().equals(userId))
                .findFirst();
    }

    @Override
    public <S extends CouponUsage> S save(S entity) {
        byId.put(entity.getId(), entity);
        return entity;
    }

    @Override
    public Optional<CouponUsage> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<CouponUsage> findAll() {
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
    public void delete(CouponUsage entity) {
        byId.remove(entity.getId());
    }

    @Override
    public void deleteAll() {
        byId.clear();
    }

    @Override
    public <S extends CouponUsage> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>();
        entities.forEach(e -> saved.add(save(e)));
        return saved;
    }

    // ---- Unused JpaRepository surface ----------------------------------------------------------

    @Override public void flush() { }
    @Override public <S extends CouponUsage> S saveAndFlush(S entity) { return save(entity); }
    @Override public <S extends CouponUsage> List<S> saveAllAndFlush(Iterable<S> entities) { throw unsupported(); }
    @Override public void deleteAllInBatch(Iterable<CouponUsage> entities) { throw unsupported(); }
    @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) { throw unsupported(); }
    @Override public void deleteAllInBatch() { byId.clear(); }
    @Override public CouponUsage getOne(UUID id) { throw unsupported(); }
    @Override public CouponUsage getById(UUID id) { throw unsupported(); }
    @Override public CouponUsage getReferenceById(UUID id) { throw unsupported(); }
    @Override public List<CouponUsage> findAllById(Iterable<UUID> ids) { throw unsupported(); }
    @Override public void deleteAllById(Iterable<? extends UUID> ids) { throw unsupported(); }
    @Override public void deleteAll(Iterable<? extends CouponUsage> entities) { throw unsupported(); }
    @Override public List<CouponUsage> findAll(Sort sort) { throw unsupported(); }
    @Override public Page<CouponUsage> findAll(Pageable pageable) { throw unsupported(); }
    @Override public <S extends CouponUsage> Optional<S> findOne(Example<S> example) { throw unsupported(); }
    @Override public <S extends CouponUsage> List<S> findAll(Example<S> example) { throw unsupported(); }
    @Override public <S extends CouponUsage> List<S> findAll(Example<S> example, Sort sort) { throw unsupported(); }
    @Override public <S extends CouponUsage> Page<S> findAll(Example<S> example, Pageable pageable) { throw unsupported(); }
    @Override public <S extends CouponUsage> long count(Example<S> example) { throw unsupported(); }
    @Override public <S extends CouponUsage> boolean exists(Example<S> example) { throw unsupported(); }
    @Override public <S extends CouponUsage, R> R findBy(Example<S> example,
            Function<FetchableFluentQuery<S>, R> queryFunction) { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not needed for unit tests");
    }
}
