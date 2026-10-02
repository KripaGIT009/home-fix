package com.homefix.dispatch.adapter;

import com.homefix.dispatch.domain.JobOffer;
import com.homefix.dispatch.domain.OfferStatus;
import com.homefix.dispatch.port.JobOfferStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Redis-backed {@link JobOfferStore}.
 *
 * <p>Layout:
 * <ul>
 *   <li>{@code dispatch:offer:booking:{bookingId}} — a hash holding the booking's current offer.
 *       Dispatch offers a booking to one provider at a time, so one key per booking suffices; a
 *       later offer to the next candidate replaces it.</li>
 *   <li>{@code dispatch:offers:provider:{providerId}} — a set of booking ids offered to that
 *       provider, so they can list their pending offers. Entries can go stale (the offer was
 *       replaced or expired from storage); readers check each against the booking's hash and
 *       stale members are pruned as they are found.</li>
 * </ul>
 * Both keys carry the TTL passed to {@link #save}, so abandoned offers clean themselves up.
 *
 * <p>{@link #update} is an optimistic compare-and-set: {@code WATCH} the hash, read it, compute the
 * transition in Java, and write it inside {@code MULTI}/{@code EXEC}. If anyone touched the hash in
 * between, {@code EXEC} is discarded and the whole read-compute-write is retried against the new
 * value, so two concurrent decisions (accept vs. the timeout, or a double accept) can never both
 * win. Keeping the transition in Java rather than a Lua script keeps the state machine in one
 * place, {@link JobOffer}, where it is unit-tested.
 *
 * <p>Registered by {@code DispatchAdaptersConfig}, for the same reason as
 * {@link RedisDistributedLockAdapter}.
 */
public class RedisJobOfferStore implements JobOfferStore {

    private static final Logger log = LoggerFactory.getLogger(RedisJobOfferStore.class);

    static final String OFFER_KEY_PREFIX = "dispatch:offer:booking:";
    static final String PROVIDER_INDEX_PREFIX = "dispatch:offers:provider:";

    /** Optimistic retries before giving up; contention on one offer is at most a handful of writers. */
    static final int MAX_CAS_ATTEMPTS = 8;

    private static final String BOOKING_ID = "bookingId";
    private static final String PROVIDER_ID = "providerId";
    private static final String STATUS = "status";
    private static final String OFFERED_AT = "offeredAt";
    private static final String EXPIRES_AT = "expiresAt";
    private static final String DECIDED_AT = "decidedAt";
    private static final String EMERGENCY = "emergency";
    private static final String SUBCATEGORY_ID = "subcategoryId";
    private static final String REFERENCE = "reference";
    private static final String SCHEDULED_AT = "scheduledAt";

    private final StringRedisTemplate redis;

    public RedisJobOfferStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(JobOffer offer, Duration ttl) {
        String offerKey = offerKey(offer.bookingId());
        String indexKey = indexKey(offer.providerId());
        Map<String, String> fields = toHash(offer);
        redis.execute(new SessionCallback<List<Object>>() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public List<Object> execute(RedisOperations operations) throws DataAccessException {
                RedisOperations<String, String> ops = operations;
                ops.multi();
                // Delete first so no field of a previous offer for this booking survives.
                ops.delete(offerKey);
                ops.opsForHash().putAll(offerKey, fields);
                ops.expire(offerKey, ttl);
                ops.opsForSet().add(indexKey, offer.bookingId().toString());
                ops.expire(indexKey, ttl);
                return ops.exec();
            }
        });
    }

    @Override
    public Optional<JobOffer> find(UUID bookingId) {
        Map<Object, Object> entries = redis.opsForHash().entries(offerKey(bookingId));
        return entries == null || entries.isEmpty() ? Optional.empty() : Optional.of(fromHash(entries));
    }

    @Override
    public List<JobOffer> findByProvider(UUID providerId) {
        String indexKey = indexKey(providerId);
        Set<String> members = redis.opsForSet().members(indexKey);
        List<JobOffer> offers = new ArrayList<>();
        if (members == null) {
            return offers;
        }
        for (String member : members) {
            Optional<JobOffer> offer = parseUuid(member).flatMap(this::find);
            if (offer.isPresent() && offer.get().isOfferedTo(providerId)) {
                offers.add(offer.get());
            } else {
                // Expired from storage or since re-offered to someone else: drop the index entry.
                redis.opsForSet().remove(indexKey, member);
            }
        }
        return offers;
    }

    @Override
    public Optional<Change> update(UUID bookingId, UnaryOperator<JobOffer> transition) {
        String key = offerKey(bookingId);
        for (int attempt = 1; attempt <= MAX_CAS_ATTEMPTS; attempt++) {
            CasResult result = redis.execute(new SessionCallback<CasResult>() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public CasResult execute(RedisOperations operations) throws DataAccessException {
                    RedisOperations<String, String> ops = operations;
                    ops.watch(key);
                    Map<Object, Object> entries = ops.opsForHash().entries(key);
                    if (entries == null || entries.isEmpty()) {
                        ops.unwatch();
                        return CasResult.committed(Optional.empty());
                    }
                    JobOffer before = fromHash(entries);
                    JobOffer after = transition.apply(before);
                    if (after.equals(before)) {
                        ops.unwatch();
                        return CasResult.committed(Optional.of(new Change(before, before)));
                    }
                    ops.multi();
                    ops.opsForHash().putAll(key, toHash(after));
                    // HMSET's reply is a bare status, which RedisTemplate.exec() drops from its
                    // results, so a committed transaction holding only the write also comes back
                    // empty — indistinguishable from a discarded one. Without this the first
                    // accept committed, was read as a conflict, and the retry then refused the
                    // provider's own decision as OFFER_ALREADY_DECIDED. HLEN's integer reply is
                    // always kept, so a committed transaction is never empty.
                    ops.opsForHash().size(key);
                    List<Object> exec = ops.exec();
                    // A discarded transaction (the key changed under the WATCH) comes back null or
                    // empty depending on the driver.
                    if (exec == null || exec.isEmpty()) {
                        return CasResult.CONFLICT;
                    }
                    return CasResult.committed(Optional.of(new Change(before, after)));
                }
            });
            if (result != null && !result.conflict()) {
                return result.change();
            }
            log.debug("Concurrent write to offer for booking {}; retrying ({}/{})",
                    bookingId, attempt, MAX_CAS_ATTEMPTS);
        }
        throw new IllegalStateException("Could not update the offer for booking " + bookingId
                + " after " + MAX_CAS_ATTEMPTS + " attempts under contention");
    }

    static String offerKey(UUID bookingId) {
        return OFFER_KEY_PREFIX + bookingId;
    }

    static String indexKey(UUID providerId) {
        return PROVIDER_INDEX_PREFIX + providerId;
    }

    static Map<String, String> toHash(JobOffer offer) {
        Map<String, String> fields = new HashMap<>();
        fields.put(BOOKING_ID, offer.bookingId().toString());
        fields.put(PROVIDER_ID, offer.providerId().toString());
        fields.put(STATUS, offer.status().name());
        fields.put(OFFERED_AT, Long.toString(offer.offeredAt().toEpochMilli()));
        fields.put(EXPIRES_AT, Long.toString(offer.expiresAt().toEpochMilli()));
        fields.put(EMERGENCY, Boolean.toString(offer.emergency()));
        if (offer.decidedAt() != null) {
            fields.put(DECIDED_AT, Long.toString(offer.decidedAt().toEpochMilli()));
        }
        if (offer.subcategoryId() != null) {
            fields.put(SUBCATEGORY_ID, offer.subcategoryId().toString());
        }
        if (offer.reference() != null) {
            fields.put(REFERENCE, offer.reference());
        }
        if (offer.scheduledAt() != null) {
            fields.put(SCHEDULED_AT, Long.toString(offer.scheduledAt().toEpochMilli()));
        }
        return fields;
    }

    static JobOffer fromHash(Map<Object, Object> entries) {
        return new JobOffer(
                UUID.fromString(str(entries, BOOKING_ID)),
                UUID.fromString(str(entries, PROVIDER_ID)),
                OfferStatus.valueOf(str(entries, STATUS)),
                instant(entries, OFFERED_AT),
                instant(entries, EXPIRES_AT),
                instant(entries, DECIDED_AT),
                Boolean.parseBoolean(str(entries, EMERGENCY)),
                str(entries, SUBCATEGORY_ID) == null ? null : UUID.fromString(str(entries, SUBCATEGORY_ID)),
                str(entries, REFERENCE),
                instant(entries, SCHEDULED_AT));
    }

    private static String str(Map<Object, Object> entries, String field) {
        Object value = entries.get(field);
        return value == null ? null : value.toString();
    }

    private static Instant instant(Map<Object, Object> entries, String field) {
        String value = str(entries, field);
        return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value));
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Outcome of one optimistic attempt: committed with a result, or lost to a concurrent write. */
    private record CasResult(boolean conflict, Optional<Change> change) {
        static final CasResult CONFLICT = new CasResult(true, Optional.empty());

        static CasResult committed(Optional<Change> change) {
            return new CasResult(false, change);
        }
    }
}
