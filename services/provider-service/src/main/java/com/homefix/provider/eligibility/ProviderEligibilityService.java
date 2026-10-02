package com.homefix.provider.eligibility;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.provider.config.ProviderProperties;
import com.homefix.provider.domain.ProviderProfile;
import com.homefix.provider.domain.ProviderProfileRepository;
import com.homefix.provider.service.ProviderException;
import com.homefix.provider.verification.VerificationClientPort;

/**
 * Finds the providers eligible for a dispatch search and scores them (Requirements 8.2, 8.3),
 * behind {@code GET /internal/providers/eligible}.
 *
 * <p>Three stages, cheapest first:
 * <ol>
 *   <li><b>SQL pre-filter</b> ({@link ProviderProfileRepository#findDispatchCandidates}): base
 *       location inside the search circle's bounding box, not under review, emergency flag, and
 *       at least one required skill tag. Only these rows are loaded.</li>
 *   <li><b>In-memory rules and scores</b> ({@link EligibilityEvaluator}): exact haversine distance
 *       against each provider's own radius, the availability schedule, and the five
 *       components.</li>
 *   <li><b>Verification</b> ({@link VerificationClientPort}): one batch call for the survivors;
 *       only {@code APPROVED} providers are returned. Asked last so the Verification Service only
 *       hears about providers who would otherwise qualify, and fails closed.</li>
 * </ol>
 *
 * <p>The SQL box is sized by {@code min(radiusKm, max-service-radius-km)}: no provider can be
 * eligible beyond their own radius, which is capped at that maximum, so a dispatcher-widened
 * search never scans further than any provider could travel.
 *
 * <p>Results are ordered closest first. The Dispatch Engine ranks by its weighted score anyway;
 * the order only makes the response deterministic.
 */
@Service
public class ProviderEligibilityService {

    private static final Logger log = LoggerFactory.getLogger(ProviderEligibilityService.class);

    private final ProviderProfileRepository profileRepository;
    private final VerificationClientPort verificationClient;
    private final ProviderProperties props;
    private final Clock clock;

    @Autowired
    public ProviderEligibilityService(ProviderProfileRepository profileRepository,
                                      VerificationClientPort verificationClient,
                                      ProviderProperties props) {
        this(profileRepository, verificationClient, props, Clock.systemUTC());
    }

    /** Fixed-clock constructor so tests can place "now" inside or outside a slot. */
    ProviderEligibilityService(ProviderProfileRepository profileRepository,
                               VerificationClientPort verificationClient,
                               ProviderProperties props,
                               Clock clock) {
        this.profileRepository = profileRepository;
        this.verificationClient = verificationClient;
        this.props = props;
        this.clock = clock;
    }

    /**
     * @throws ProviderException 400 when the coordinates or radius are out of range
     */
    @Transactional(readOnly = true)
    public List<ProviderMatch> findEligible(EligibilityQuery query) {
        validate(query);
        if (query.skillTags().isEmpty()) {
            // Requirement 8.2 needs at least one shared tag; with none required nobody can share
            // one. The Dispatch Engine refuses such bookings before asking, so this is a guard.
            log.debug("Eligibility search for subcategory {} carried no skill tags; no candidates",
                    query.subcategoryId());
            return List.of();
        }

        double searchRadiusKm = Math.min(query.radiusKm(), props.getMaxServiceRadiusKm());
        GeoMath.BoundingBox box = GeoMath.boundingBox(query.latitude(), query.longitude(), searchRadiusKm);
        List<ProviderProfile> candidates = profileRepository.findDispatchCandidates(
                box.minLat(), box.maxLat(), box.minLon(), box.maxLon(),
                query.emergency(), query.skillTags());

        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of(props.getAvailabilityZone()));
        List<ProviderMatch> matches = new ArrayList<>();
        for (ProviderProfile candidate : candidates) {
            Optional<ProviderMatch> match = EligibilityEvaluator.evaluate(candidate, query, now);
            match.ifPresent(matches::add);
        }
        if (matches.isEmpty()) {
            log.debug("Eligibility search for subcategory {}: {} pre-filtered, none eligible",
                    query.subcategoryId(), candidates.size());
            return List.of();
        }

        Set<UUID> approved = verificationClient.approvedAmong(
                matches.stream().map(ProviderMatch::providerId).toList());
        List<ProviderMatch> eligible = matches.stream()
                .filter(m -> approved.contains(m.providerId()))
                .sorted(Comparator.comparingDouble(ProviderMatch::distanceKm)
                        .thenComparing(ProviderMatch::providerId))
                .toList();
        log.debug("Eligibility search for subcategory {}: {} pre-filtered, {} matched, {} approved",
                query.subcategoryId(), candidates.size(), matches.size(), eligible.size());
        return eligible;
    }

    private static void validate(EligibilityQuery query) {
        if (!Double.isFinite(query.latitude()) || query.latitude() < -90.0 || query.latitude() > 90.0) {
            throw ProviderException.validation("lat must be between -90 and 90 (got " + query.latitude() + ")");
        }
        if (!Double.isFinite(query.longitude()) || query.longitude() < -180.0 || query.longitude() > 180.0) {
            throw ProviderException.validation("lon must be between -180 and 180 (got " + query.longitude() + ")");
        }
        if (!Double.isFinite(query.radiusKm()) || query.radiusKm() <= 0) {
            throw ProviderException.validation("radiusKm must be greater than 0 (got " + query.radiusKm() + ")");
        }
    }
}
