package com.homefix.pricing.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.homefix.pricing.cache.PricingConfigCachePort;
import com.homefix.pricing.config.PricingParametersPort;
import com.homefix.pricing.domain.PricingParameters;

/**
 * Read-through accessor and Admin writer for per-subcategory pricing parameters
 * (Requirement 6.11).
 *
 * <p>Reads consult the {@link PricingConfigCachePort} first and fall back to the
 * {@link PricingParametersPort} source of truth on a miss, repopulating the cache. Admin writes
 * persist through the source of truth and invalidate the cache so the next read repopulates —
 * guaranteeing updated parameters reach new bookings within the cache TTL (default 60 s).
 */
@Service
public class PricingConfigService {

    /** Row cap on the Admin listing (the portal's DataTable takes a bare, unpaged array). */
    public static final int ADMIN_LIST_LIMIT = 200;

    /**
     * Upper sanity bound on a stored emergency or surge multiplier. The engine still clamps to its
     * configured maxima ({@code homefix.pricing.max-*-multiplier}) at quote time; this bound only
     * catches values that cannot be meant as a multiplier, such as a percent typed as {@code 150}.
     */
    public static final BigDecimal MAX_MULTIPLIER = BigDecimal.TEN;

    private final PricingConfigCachePort cache;
    private final PricingParametersPort parametersPort;

    public PricingConfigService(PricingConfigCachePort cache, PricingParametersPort parametersPort) {
        this.cache = cache;
        this.parametersPort = parametersPort;
    }

    /**
     * Resolves the effective pricing parameters for a subcategory, read-through the cache.
     *
     * @throws PricingException if no parameters are configured for the subcategory.
     */
    public PricingParameters requireParameters(UUID subcategoryId) {
        return cache.get(subcategoryId).orElseGet(() -> {
            PricingParameters loaded = parametersPort.findBySubcategoryId(subcategoryId)
                    .orElseThrow(() -> new PricingException(HttpStatus.NOT_FOUND,
                            "PRICING_PARAMETERS_NOT_FOUND",
                            "No pricing parameters configured for subcategory " + subcategoryId));
            cache.put(loaded);
            return loaded;
        });
    }

    /**
     * Admin update of pricing parameters. Persists through the source of truth and invalidates
     * the cache entry so the change is reflected on the next read within the TTL bound
     * (Requirement 6.11).
     *
     * @throws PricingException 400 {@code VALIDATION_ERROR} when a value is out of range (see
     *                          {@link #requireInRange})
     */
    public PricingParameters updateParameters(PricingParameters parameters) {
        requireInRange(parameters);
        PricingParameters saved = parametersPort.save(parameters);
        cache.invalidate(saved.subcategoryId());
        return saved;
    }

    /**
     * Every configured parameter set for the Admin Portal's pricing table, most recently updated
     * first and capped at {@link #ADMIN_LIST_LIMIT}. Read from the source of truth: the cache is a
     * per-subcategory lookup and cannot enumerate.
     */
    public List<PricingParameters> listParameters() {
        return parametersPort.findAll(ADMIN_LIST_LIMIT);
    }

    /**
     * Admin Portal update: applies {@code changes} on top of the stored parameters, where a
     * {@code null} field means "keep the stored value". The portal edits only a subset of the
     * parameters (it never sees {@code taxRate} or the override floor/ceiling), so replacing the
     * whole set as {@link #updateParameters} does would silently clear them.
     *
     * <p>The merge base is read from the source of truth, not the cache, so a stale cache entry
     * can never be written back. Like {@link #updateParameters}, an unconfigured subcategory is
     * created; the same rule then applies as for the parameters PUT — a base price is required.
     * The write itself goes through {@link #updateParameters}, so persistence and cache
     * invalidation are identical for both Admin entry points.
     *
     * @throws PricingException {@code VALIDATION_ERROR} if the merged set has no base price.
     */
    public PricingParameters mergeParameters(PricingParameters changes) {
        PricingParameters merged = parametersPort.findBySubcategoryId(changes.subcategoryId())
                .map(stored -> merge(stored, changes))
                .orElse(changes);
        if (merged.basePrice() == null) {
            throw PricingException.validation(
                    "basePrice is required to configure subcategory " + changes.subcategoryId());
        }
        return updateParameters(merged);
    }

    /**
     * Range-checks a parameter set before it is stored. Both Admin entry points end here, the
     * portal's merge after merging, so a stored value is never one the engine would have to
     * silently correct at quote time:
     * <ul>
     *   <li>every amount (base price, per-km rate, travel cap, surcharges, override floor and
     *       ceiling) is zero or more;</li>
     *   <li>the platform-fee and tax rates are fractions between 0 and 1 (0% to 100%);</li>
     *   <li>the emergency and surge multipliers are between 1 (no uplift) and
     *       {@link #MAX_MULTIPLIER};</li>
     *   <li>the override floor is not above the override ceiling.</li>
     * </ul>
     * Absent (null) values are not checked here: they mean "not configured" to the engine.
     *
     * @throws PricingException 400 {@code VALIDATION_ERROR} listing every violation in its details
     */
    static void requireInRange(PricingParameters p) {
        List<String> errors = new ArrayList<>();
        nonNegative(errors, "basePrice", p.basePrice());
        nonNegative(errors, "perKmRate", p.perKmRate());
        nonNegative(errors, "maxTravelCharge", p.maxTravelCharge());
        nonNegative(errors, "nightSurcharge", p.nightSurcharge());
        nonNegative(errors, "weekendSurcharge", p.weekendSurcharge());
        nonNegative(errors, "overrideFloor", p.overrideFloor());
        nonNegative(errors, "overrideCeiling", p.overrideCeiling());
        between(errors, "platformFeeRate", p.platformFeeRate(), BigDecimal.ZERO, BigDecimal.ONE);
        between(errors, "taxRate", p.taxRate(), BigDecimal.ZERO, BigDecimal.ONE);
        between(errors, "emergencyMultiplier", p.emergencyMultiplier(), BigDecimal.ONE, MAX_MULTIPLIER);
        between(errors, "surgeMultiplier", p.surgeMultiplier(), BigDecimal.ONE, MAX_MULTIPLIER);
        if (p.overrideFloor() != null && p.overrideCeiling() != null
                && p.overrideFloor().compareTo(p.overrideCeiling()) > 0) {
            errors.add("overrideFloor must not be greater than overrideCeiling");
        }
        if (!errors.isEmpty()) {
            throw PricingException.validation(
                    "Pricing parameters for subcategory " + p.subcategoryId() + " are out of range",
                    errors);
        }
    }

    private static void nonNegative(List<String> errors, String field, BigDecimal value) {
        if (value != null && value.signum() < 0) {
            errors.add(field + " must not be negative");
        }
    }

    private static void between(List<String> errors, String field, BigDecimal value,
                                BigDecimal min, BigDecimal max) {
        if (value != null && (value.compareTo(min) < 0 || value.compareTo(max) > 0)) {
            errors.add(field + " must be between " + min.toPlainString() + " and " + max.toPlainString());
        }
    }

    private static PricingParameters merge(PricingParameters stored, PricingParameters changes) {
        return new PricingParameters(stored.subcategoryId(),
                pick(changes.basePrice(), stored.basePrice()),
                pick(changes.perKmRate(), stored.perKmRate()),
                pick(changes.maxTravelCharge(), stored.maxTravelCharge()),
                pick(changes.nightSurcharge(), stored.nightSurcharge()),
                pick(changes.weekendSurcharge(), stored.weekendSurcharge()),
                pick(changes.platformFeeRate(), stored.platformFeeRate()),
                pick(changes.taxRate(), stored.taxRate()),
                pick(changes.emergencyMultiplier(), stored.emergencyMultiplier()),
                pick(changes.surgeMultiplier(), stored.surgeMultiplier()),
                pick(changes.overrideFloor(), stored.overrideFloor()),
                pick(changes.overrideCeiling(), stored.overrideCeiling()));
    }

    private static BigDecimal pick(BigDecimal change, BigDecimal stored) {
        return change != null ? change : stored;
    }
}
