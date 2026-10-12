package com.homefix.dispatch.adapter;

import com.homefix.dispatch.domain.JobOffer;
import com.homefix.dispatch.domain.OfferRound;
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
import java.util.Comparator;
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
 *   <li>{@code dispatch:offer:booking:{bookingId}} — a hash holding the booking's whole
 *       {@link OfferRound}: the booking-level fields once ({@code bookingId}, {@code emergency},
 *       {@code subcategoryId}, {@code reference}, {@code scheduledAt}) and one
 *       {@code offer:{providerId}} field per provider offered, encoded as
 *       {@code STATUS|offeredAtMillis|expiresAtMillis|decidedAtMillis|position}, where position
 *       keeps the order the offers were made in. Keeping every offer in one
 *       key is what lets a single compare-and-set accept one provider and withdraw the others.</li>
 *   <li>{@code dispatch:offers:provider:{providerId}} — a set of booking ids offered to that
 *       provider, so they can list their pending offers. Entries can go stale (the round expired
 *       from storage); readers check each against the booking's hash and prune stale members.</li>
 * </ul>
 * Both keys carry the TTL passed when a wave is opened, so abandoned offers clean themselves up.
 * Fields are only ever added or overwritten (offers are never removed from a round), so later
 * writes without a TTL keep the key's expiry.
 *
 * <p>{@link #update} is an optimistic compare-and-set: {@code WATCH} the hash, read it, compute the
 * transition in Java, and write it inside {@code MULTI}/{@code EXEC}. If anyone touched the hash in
 * between, {@code EXEC} is discarded and the whole read-compute-write is retried against the new
 * value, so two concurrent decisions (two accepts, or an accept and the wave's timeout) can never
 * both win. Keeping the transition in Java rather than a Lua script keeps the state machine in one
 * place, {@link OfferRound}, where it is unit-tested.
 *
 * <p>Registered by {@code DispatchAdaptersConfig}, for the same reason as
 * {@link RedisDistributedLockAdapter}.
 */
public class RedisJobOfferStore implements JobOfferStore {

    private static final Logger log = LoggerFactory.getLogger(RedisJobOfferStore.class);

    static final String OFFER_KEY_PREFIX = "dispatch:offer:booking:";
    static final String PROVIDER_INDEX_PREFIX = "dispatch:offers:provider:";
    static final String OFFER_FIELD_PREFIX = "offer:";

    /** Optimistic retries before giving up; contention on one round is at most a wave's providers. */
    static final int MAX_CAS_ATTEMPTS = 16;

    private static final String BOOKING_ID = "bookingId";
    private static final String EMERGENCY = "emergency";
    private static final String SUBCATEGORY_ID = "subcategoryId";
    private static final String REFERENCE = "reference";
    private static final String SCHEDULED_AT = "scheduledAt";

    // Single-offer layout written before offers were made in waves; still read so offers in
    // flight across a deploy are not lost (they expire within one window plus grace).
    private static final String LEGACY_PROVIDER_ID = "providerId";
    private static final String LEGACY_STATUS = "status";
    private static final String LEGACY_OFFERED_AT = "offeredAt";
    private static final String LEGACY_EXPIRES_AT = "expiresAt";
    private static final String LEGACY_DECIDED_AT = "decidedAt";

    private final StringRedisTemplate redis;

    public RedisJobOfferStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<OfferRound> find(UUID bookingId) {
        Map<Object, Object> entries = redis.opsForHash().entries(offerKey(bookingId));
        return entries == null || entries.isEmpty() ? Optional.empty() : Optional.of(fromHash(bookingId, entries));
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
            Optional<JobOffer> offer = parseUuid(member).flatMap(this::find).flatMap(r -> r.offerTo(providerId));
            if (offer.isPresent()) {
                offers.add(offer.get());
            } else {
                // The round expired from storage: drop the index entry.
                redis.opsForSet().remove(indexKey, member);
            }
        }
        return offers;
    }

    @Override
    public Change update(UUID bookingId, UnaryOperator<OfferRound> transition, Duration ttl) {
        String key = offerKey(bookingId);
        for (int attempt = 1; attempt <= MAX_CAS_ATTEMPTS; attempt++) {
            CasResult result = redis.execute(new SessionCallback<CasResult>() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public CasResult execute(RedisOperations operations) throws DataAccessException {
                    RedisOperations<String, String> ops = operations;
                    ops.watch(key);
                    Map<Object, Object> entries = ops.opsForHash().entries(key);
                    OfferRound before = entries == null || entries.isEmpty()
                            ? OfferRound.empty(bookingId)
                            : fromHash(bookingId, entries);
                    OfferRound after = transition.apply(before);
                    if (after.equals(before)) {
                        ops.unwatch();
                        return CasResult.committed(new Change(before, before));
                    }
                    ops.multi();
                    ops.opsForHash().putAll(key, toHash(after));
                    if (ttl != null) {
                        ops.expire(key, ttl);
                    }
                    for (JobOffer offer : after.offers()) {
                        if (before.offerTo(offer.providerId()).isEmpty()) {
                            String indexKey = indexKey(offer.providerId());
                            ops.opsForSet().add(indexKey, bookingId.toString());
                            if (ttl != null) {
                                ops.expire(indexKey, ttl);
                            }
                        }
                    }
                    // HMSET's reply is a bare status, which RedisTemplate.exec() drops from its
                    // results, so a committed transaction holding only the write would also come
                    // back empty — indistinguishable from a discarded one. HLEN's integer reply is
                    // always kept, so a committed transaction is never empty.
                    ops.opsForHash().size(key);
                    List<Object> exec = ops.exec();
                    // A discarded transaction (the key changed under the WATCH) comes back null or
                    // empty depending on the driver.
                    if (exec == null || exec.isEmpty()) {
                        return CasResult.CONFLICT;
                    }
                    return CasResult.committed(new Change(before, after));
                }
            });
            if (result != null && !result.conflict()) {
                return result.change();
            }
            log.debug("Concurrent write to the offers of booking {}; retrying ({}/{})",
                    bookingId, attempt, MAX_CAS_ATTEMPTS);
        }
        throw new IllegalStateException("Could not update the offers of booking " + bookingId
                + " after " + MAX_CAS_ATTEMPTS + " attempts under contention");
    }

    static String offerKey(UUID bookingId) {
        return OFFER_KEY_PREFIX + bookingId;
    }

    static String indexKey(UUID providerId) {
        return PROVIDER_INDEX_PREFIX + providerId;
    }

    static Map<String, String> toHash(OfferRound round) {
        Map<String, String> fields = new HashMap<>();
        fields.put(BOOKING_ID, round.bookingId().toString());
        if (round.offers().isEmpty()) {
            return fields;
        }
        JobOffer first = round.offers().get(0);
        fields.put(EMERGENCY, Boolean.toString(first.emergency()));
        if (first.subcategoryId() != null) {
            fields.put(SUBCATEGORY_ID, first.subcategoryId().toString());
        }
        if (first.reference() != null) {
            fields.put(REFERENCE, first.reference());
        }
        if (first.scheduledAt() != null) {
            fields.put(SCHEDULED_AT, Long.toString(first.scheduledAt().toEpochMilli()));
        }
        for (int position = 0; position < round.offers().size(); position++) {
            JobOffer offer = round.offers().get(position);
            fields.put(OFFER_FIELD_PREFIX + offer.providerId(), encode(offer, position));
        }
        return fields;
    }

    static OfferRound fromHash(UUID bookingId, Map<Object, Object> entries) {
        boolean emergency = Boolean.parseBoolean(str(entries, EMERGENCY));
        UUID subcategoryId = str(entries, SUBCATEGORY_ID) == null ? null : UUID.fromString(str(entries, SUBCATEGORY_ID));
        String reference = str(entries, REFERENCE);
        Instant scheduledAt = instant(str(entries, SCHEDULED_AT));
        List<JobOffer> offers = new ArrayList<>();
        Map<UUID, Integer> positions = new HashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String field = entry.getKey().toString();
            if (!field.startsWith(OFFER_FIELD_PREFIX)) {
                continue;
            }
            UUID providerId = UUID.fromString(field.substring(OFFER_FIELD_PREFIX.length()));
            String[] parts = entry.getValue().toString().split("\\|", -1);
            positions.put(providerId, parts.length > 4 ? Integer.parseInt(parts[4]) : 0);
            offers.add(new JobOffer(bookingId, providerId, OfferStatus.valueOf(parts[0]),
                    instant(parts[1]), instant(parts[2]), instant(parts[3]),
                    emergency, subcategoryId, reference, scheduledAt));
        }
        if (offers.isEmpty() && str(entries, LEGACY_PROVIDER_ID) != null) {
            offers.add(new JobOffer(bookingId, UUID.fromString(str(entries, LEGACY_PROVIDER_ID)),
                    OfferStatus.valueOf(str(entries, LEGACY_STATUS)),
                    instant(str(entries, LEGACY_OFFERED_AT)), instant(str(entries, LEGACY_EXPIRES_AT)),
                    instant(str(entries, LEGACY_DECIDED_AT)), emergency, subcategoryId, reference, scheduledAt));
        }
        // Hash order is arbitrary; put the round back in the order the offers were made.
        offers.sort(Comparator.comparing((JobOffer o) -> positions.getOrDefault(o.providerId(), 0)));
        return new OfferRound(bookingId, offers);
    }

    private static String encode(JobOffer offer, int position) {
        return offer.status().name()
                + '|' + offer.offeredAt().toEpochMilli()
                + '|' + offer.expiresAt().toEpochMilli()
                + '|' + (offer.decidedAt() == null ? "" : Long.toString(offer.decidedAt().toEpochMilli()))
                + '|' + position;
    }

    private static String str(Map<Object, Object> entries, String field) {
        Object value = entries.get(field);
        return value == null ? null : value.toString();
    }

    private static Instant instant(String value) {
        return value == null || value.isEmpty() ? null : Instant.ofEpochMilli(Long.parseLong(value));
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Outcome of one optimistic attempt: committed with a result, or lost to a concurrent write. */
    private record CasResult(boolean conflict, Change change) {
        static final CasResult CONFLICT = new CasResult(true, null);

        static CasResult committed(Change change) {
            return new CasResult(false, change);
        }
    }
}
