package com.homefix.payment.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;

import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.domain.PaymentTransactionRepository;

/**
 * Minimal in-memory {@link PaymentTransactionRepository} for service unit tests, so state
 * mutations persist across calls without a database or Spring context. Only the methods used by
 * {@code PaymentService} are implemented; the rest throw {@link UnsupportedOperationException}.
 */
public class InMemoryPaymentTransactionRepository implements PaymentTransactionRepository {

    private final Map<UUID, PaymentTransaction> store = new LinkedHashMap<>();

    @Override
    public Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey) {
        return store.values().stream()
                .filter(t -> idempotencyKey.equals(t.getIdempotencyKey()))
                .findFirst();
    }

    @Override
    public Optional<PaymentTransaction> findByGatewayReference(String gatewayReference) {
        return store.values().stream()
                .filter(t -> gatewayReference != null && gatewayReference.equals(t.getGatewayReference()))
                .findFirst();
    }

    @Override
    public Optional<PaymentTransaction> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    /** No real row lock in memory; tests run single-threaded. */
    @Override
    public Optional<PaymentTransaction> findByIdForUpdate(UUID id) {
        return findById(id);
    }

    @Override
    public List<PaymentTransaction> findWalletCreditsDue(Instant cutoff, Pageable page) {
        return store.values().stream()
                .filter(t -> t.getWalletCreditPendingSince() != null
                        && !t.getWalletCreditPendingSince().isAfter(cutoff))
                .sorted(Comparator.comparing(PaymentTransaction::getWalletCreditPendingSince))
                .limit(page.getPageSize())
                .toList();
    }

    /**
     * Interprets the LIKE pattern the way the database does ({@code %}, {@code _}, {@code !}
     * escape), over the same three columns, newest first with the id as tie-breaker.
     */
    @Override
    public List<PaymentTransaction> searchForAdmin(String pattern, Pageable page) {
        Pattern regex = likeToRegex(pattern);
        return store.values().stream()
                .filter(t -> regex.matcher(t.getId().toString()).matches()
                        || regex.matcher(t.getBookingId().toString()).matches()
                        || (t.getGatewayReference() != null
                                && regex.matcher(t.getGatewayReference().toLowerCase(Locale.ROOT)).matches()))
                .sorted(Comparator.comparing(PaymentTransaction::getCreatedAt)
                        .thenComparing(PaymentTransaction::getId).reversed())
                .skip(page.getOffset())
                .limit(page.getPageSize())
                .toList();
    }

    private static Pattern likeToRegex(String like) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < like.length(); i++) {
            char c = like.charAt(i);
            if (c == '!' && i + 1 < like.length()) {
                regex.append(Pattern.quote(String.valueOf(like.charAt(++i))));
            } else if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    /** Like the real bulk update, changes only the marker column and leaves the entity otherwise alone. */
    @Override
    public int clearWalletCreditPending(UUID id) {
        PaymentTransaction tx = store.get(id);
        if (tx == null) {
            return 0;
        }
        tx.clearWalletCreditPending();
        return 1;
    }

    @Override
    public int markWalletCreditFailed(UUID id, String reason) {
        PaymentTransaction tx = store.get(id);
        if (tx == null) {
            return 0;
        }
        tx.markWalletCreditFailed(reason);
        return 1;
    }

    @Override
    public <S extends PaymentTransaction> S save(S entity) {
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
    public List<PaymentTransaction> findAll() {
        return new ArrayList<>(store.values());
    }

    // ---- Unused JpaRepository surface ----

    @Override
    public <S extends PaymentTransaction> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>();
        entities.forEach(e -> saved.add(save(e)));
        return saved;
    }

    @Override
    public List<PaymentTransaction> findAll(Sort sort) {
        return findAll();
    }

    @Override
    public List<PaymentTransaction> findAllById(Iterable<UUID> ids) {
        List<PaymentTransaction> result = new ArrayList<>();
        ids.forEach(id -> findById(id).ifPresent(result::add));
        return result;
    }

    @Override
    public void deleteById(UUID id) {
        store.remove(id);
    }

    @Override
    public void delete(PaymentTransaction entity) {
        store.remove(entity.getId());
    }

    @Override
    public void deleteAllById(Iterable<? extends UUID> ids) {
        ids.forEach(store::remove);
    }

    @Override
    public void deleteAll(Iterable<? extends PaymentTransaction> entities) {
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
    public <S extends PaymentTransaction> S saveAndFlush(S entity) {
        return save(entity);
    }

    @Override
    public <S extends PaymentTransaction> List<S> saveAllAndFlush(Iterable<S> entities) {
        return saveAll(entities);
    }

    @Override
    public void deleteAllInBatch(Iterable<PaymentTransaction> entities) {
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
    public PaymentTransaction getOne(UUID id) {
        return getReferenceById(id);
    }

    @Override
    public PaymentTransaction getById(UUID id) {
        return getReferenceById(id);
    }

    @Override
    public PaymentTransaction getReferenceById(UUID id) {
        return findById(id).orElseThrow();
    }

    @Override
    public <S extends PaymentTransaction> Optional<S> findOne(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends PaymentTransaction> List<S> findAll(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends PaymentTransaction> List<S> findAll(Example<S> example, Sort sort) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends PaymentTransaction> Page<S> findAll(Example<S> example, Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends PaymentTransaction> long count(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends PaymentTransaction> boolean exists(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Page<PaymentTransaction> findAll(Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends PaymentTransaction, R> R findBy(Example<S> example,
            java.util.function.Function<FetchableFluentQuery<S>, R> queryFunction) {
        throw new UnsupportedOperationException();
    }
}
