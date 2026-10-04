package com.homefix.provider.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.homefix.provider.auth.AuthUserClientPort;
import com.homefix.provider.auth.AuthUserClientPort.AuthUser;
import com.homefix.provider.catalog.CatalogClientPort;
import com.homefix.provider.domain.ProviderProfile;
import com.homefix.provider.domain.ProviderProfileRepository;
import com.homefix.provider.domain.Tenant;
import com.homefix.provider.domain.Tenant.TenantDetails;
import com.homefix.provider.domain.TenantAdmin;
import com.homefix.provider.domain.TenantAdminRepository;
import com.homefix.provider.domain.TenantCount;
import com.homefix.provider.domain.TenantRepository;
import com.homefix.provider.domain.TenantStatus;
import com.homefix.provider.eligibility.GeoMath;
import com.homefix.provider.service.TenantViews.AdminView;
import com.homefix.provider.service.TenantViews.CoveringTenant;
import com.homefix.provider.service.TenantViews.TenantView;

/**
 * The Tenant registry and its administrators (Requirements MT-1, MT-2), the caller-to-Tenant
 * resolution behind the Tenant Portal (Requirement MT-10.1), and the Tenant lookups booking-service
 * makes (coverage, Requirement MT-4.1; Tenant by admin, by provider and by id).
 *
 * <h2>Administrators span two services</h2>
 * The role lives in the Auth Service, the membership here, and no transaction covers both. Each
 * order is chosen so that a failure leaves a state that grants nothing extra and that retrying
 * the same operation repairs:
 * <ul>
 *   <li><b>Add</b>: grant {@code TENANT_ADMIN} first, then record the membership. If recording
 *       fails the grant is compensated (revoked) — but only when the user ends up administering no
 *       Tenant, so a concurrent add that won is never stripped of the role it legitimately got.</li>
 *   <li><b>Remove</b>: revoke first, then delete the membership. A failed revoke changes nothing
 *       (503, retry); a failed delete leaves a membership whose user no longer holds the role, which
 *       opens no portal route and which a retried removal deletes. No compensating write is needed,
 *       so no double failure can strand a role without a membership.</li>
 * </ul>
 * Neither path holds a database transaction across the HTTP call.
 *
 * <h2>Audit</h2>
 * Every Tenant, administrator and membership change is logged at INFO with the acting user's id and
 * the Tenant id, never a name or mobile number (Requirement MT-1.3); {@code updated_by} records the
 * last Platform_Admin to change the Tenant itself.
 */
@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    static final int MIN_NAME_LENGTH = 2;
    static final int MAX_NAME_LENGTH = 120;
    static final BigDecimal MIN_RADIUS_KM = BigDecimal.ONE;
    static final BigDecimal MAX_RADIUS_KM = new BigDecimal("100");
    static final int MAX_PHONE_LENGTH = 20;
    static final int MAX_EMAIL_LENGTH = 254;

    private static final Pattern PHONE = Pattern.compile("[+0-9 ()\\-]{4,20}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final TenantRepository tenants;
    private final TenantAdminRepository admins;
    private final ProviderProfileRepository profiles;
    private final CatalogClientPort catalog;
    private final AuthUserClientPort auth;

    public TenantService(TenantRepository tenants, TenantAdminRepository admins,
                         ProviderProfileRepository profiles, CatalogClientPort catalog,
                         AuthUserClientPort auth) {
        this.tenants = tenants;
        this.admins = admins;
        this.profiles = profiles;
        this.catalog = catalog;
        this.auth = auth;
    }

    // ============================= Registry (Requirement MT-1) ==========================

    /** Every Tenant with its provider and admin counts, by name (Requirement MT-1.5). */
    public List<TenantView> list() {
        List<Tenant> all = tenants.findAllForAdmin();
        if (all.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> providerCounts = toMap(profiles.countPerTenant());
        Map<UUID, Long> adminCounts = toMap(admins.countPerTenant());
        return all.stream()
                .map(t -> new TenantView(t, providerCounts.getOrDefault(t.getId(), 0L),
                        adminCounts.getOrDefault(t.getId(), 0L)))
                .toList();
    }

    /** One Tenant with its counts. @throws ProviderException 404 {@code TENANT_NOT_FOUND} */
    public TenantView get(UUID tenantId) {
        return view(requireTenant(tenantId));
    }

    /**
     * Creates an {@code ACTIVE} Tenant (Requirement MT-1.2).
     *
     * @throws ProviderException 400 naming the failed rule (see {@link #validate})
     */
    public TenantView create(TenantCommand command, UUID actor) {
        TenantDetails details = validate(command);
        Tenant saved = tenants.saveAndFlush(Tenant.create(details, actor));
        log.info("Tenant {} created by {} (radius {} km, {} categories)", saved.getId(), actor,
                saved.getServiceRadiusKm(), saved.getCategoryIds().size());
        return new TenantView(saved, 0, 0);
    }

    /**
     * Replaces a Tenant's details, area, categories and status in one statement batch
     * (Requirement MT-1.3). The version check turns a concurrent edit into 409 rather than letting
     * the later save silently undo the earlier one.
     *
     * @throws ProviderException 400 naming the failed rule, 404 {@code TENANT_NOT_FOUND}, 409
     *         {@code TENANT_CONCURRENTLY_MODIFIED}
     */
    public TenantView update(UUID tenantId, TenantCommand command, UUID actor) {
        TenantDetails details = validate(command);
        Tenant tenant = requireTenant(tenantId);
        TenantStatus previous = tenant.getStatus();
        TenantStatus status = command.status() == null ? previous : parseStatus(command.status());
        tenant.update(details, status, actor);
        Tenant saved;
        try {
            saved = tenants.saveAndFlush(tenant);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ProviderException(HttpStatus.CONFLICT, "TENANT_CONCURRENTLY_MODIFIED",
                    "The Tenant was changed by someone else; reload it and try again");
        }
        log.info("Tenant {} updated by {} (status {} -> {}, radius {} km, {} categories)", tenantId, actor,
                previous, status, saved.getServiceRadiusKm(), saved.getCategoryIds().size());
        return view(saved);
    }

    // ============================= Administrators (Requirement MT-2) ====================

    /** The Tenant's administrators. Mobile numbers are resolved per admin, best effort. */
    public List<AdminView> admins(UUID tenantId) {
        requireTenant(tenantId);
        return admins.findByTenantIdOrderByUserIdAsc(tenantId).stream()
                .map(a -> new AdminView(a.getUserId(), auth.mobileNumberOf(a.getUserId()).orElse(null)))
                .toList();
    }

    /**
     * Makes the account registered with {@code mobileNumber} an administrator of the Tenant
     * (Requirement MT-2.2). Adding an existing administrator of the same Tenant again succeeds and
     * re-grants the role (both idempotent).
     *
     * @throws ProviderException 404 {@code TENANT_NOT_FOUND} / {@code USER_NOT_FOUND}, 409
     *         {@code ADMIN_OF_OTHER_TENANT} (Requirement MT-2.3), 503 {@code AUTH_UNAVAILABLE}
     */
    public AdminView addAdmin(UUID tenantId, String mobileNumber, UUID actor) {
        requireTenant(tenantId);
        String number = normaliseMobile(mobileNumber);
        AuthUser user = auth.findByMobile(number).orElseThrow(() -> new ProviderException(
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "No account is registered with that mobile number"));
        UUID userId = user.userId();
        Optional<TenantAdmin> existing = admins.findById(userId);
        if (existing.isPresent() && !existing.get().getTenantId().equals(tenantId)) {
            throw adminOfOtherTenant();
        }

        // Grant before recording, so a recorded administrator always holds the role.
        auth.grantTenantAdmin(userId);
        if (existing.isPresent()) {
            log.info("Tenant {} admin {} re-added by {} (already an administrator)", tenantId, userId, actor);
            return new AdminView(userId, number);
        }
        try {
            admins.saveAndFlush(new TenantAdmin(tenantId, userId));
        } catch (RuntimeException e) {
            Optional<TenantAdmin> now = admins.findById(userId);
            if (now.isPresent()) {
                if (now.get().getTenantId().equals(tenantId)) {
                    // A concurrent add of the same person to the same Tenant won: same outcome.
                    return new AdminView(userId, number);
                }
                // A concurrent add to another Tenant won; its grant is legitimate, keep it.
                throw adminOfOtherTenant();
            }
            compensateGrant(tenantId, userId);
            throw e;
        }
        log.info("Tenant {} admin {} added by {}", tenantId, userId, actor);
        return new AdminView(userId, number);
    }

    /**
     * Removes an administrator (Requirement MT-2.4): the role first, which the Auth Service revokes
     * together with the user's sessions, then the membership.
     *
     * <p>Why this order. Deleting the membership first needed a compensating write (restore the
     * membership) when the revoke failed, and if that write failed too the user kept the role with no
     * membership, which a retry could never clean up because it answered 404. Revoking first needs
     * no compensation:
     * <ul>
     *   <li>revoke fails: nothing has changed, the Admin gets 503 and simply retries;</li>
     *   <li>revoke succeeds, delete fails: the user keeps a membership but has lost the role and their
     *       sessions, so once their current access token expires (it cannot be refreshed) they cannot
     *       reach the Tenant Portal, every route of which requires {@code TENANT_ADMIN}; the
     *       membership still shows in the admin list, the Admin got an error, and retrying the
     *       removal finishes it, because the revoke is idempotent.</li>
     * </ul>
     * The membership is checked before anything is revoked, so a user who administers another Tenant
     * (or none) is a 404 and keeps their role.
     *
     * @throws ProviderException 404 {@code TENANT_NOT_FOUND}, 404 {@code USER_NOT_FOUND} when the
     *         user does not administer this Tenant, 503 {@code AUTH_UNAVAILABLE} (nothing changed)
     */
    public void removeAdmin(UUID tenantId, UUID userId, UUID actor) {
        requireTenant(tenantId);
        boolean administersThisTenant = admins.findById(userId)
                .map(a -> a.getTenantId().equals(tenantId))
                .orElse(false);
        if (!administersThisTenant) {
            throw new ProviderException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                    "That user is not an administrator of this Tenant");
        }
        auth.revokeTenantAdmin(userId);
        if (admins.deleteMembership(tenantId, userId) == 0) {
            // A concurrent removal deleted it between the check and here: same outcome.
            log.info("Tenant {} admin {} was already removed concurrently", tenantId, userId);
        }
        log.info("Tenant {} admin {} removed by {}", tenantId, userId, actor);
    }

    private void compensateGrant(UUID tenantId, UUID userId) {
        try {
            auth.revokeTenantAdmin(userId);
            log.warn("Tenant {} admin {} was not recorded; TENANT_ADMIN grant revoked again", tenantId, userId);
        } catch (RuntimeException revokeFailure) {
            log.error("Tenant {} admin {} was not recorded and the TENANT_ADMIN grant could not be revoked; "
                    + "the user holds the role without a Tenant until it is revoked", tenantId, userId,
                    revokeFailure);
        }
    }

    private static ProviderException adminOfOtherTenant() {
        return new ProviderException(HttpStatus.CONFLICT, "ADMIN_OF_OTHER_TENANT",
                "That user already administers another Tenant");
    }

    // ============================= Tenant Portal context (Requirement MT-10.1) ==========

    /**
     * The Tenant the caller administers, resolved from their identity only — never from a request
     * parameter (Requirement MT-10.1, Property MT5).
     *
     * @throws ProviderException 404 {@code TENANT_NOT_FOUND} when the caller administers no Tenant
     *         (e.g. just removed, with a token issued before), 403 {@code TENANT_SUSPENDED}
     *         (Requirement MT-1.4)
     */
    public Tenant requirePortalTenant(UUID callerId) {
        Tenant tenant = admins.findById(callerId)
                .flatMap(a -> tenants.findById(a.getTenantId()))
                .orElseThrow(() -> new ProviderException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND",
                        "You do not administer a Tenant"));
        if (!tenant.isActive()) {
            throw new ProviderException(HttpStatus.FORBIDDEN, "TENANT_SUSPENDED",
                    "Your Tenant is suspended; contact HomeFix");
        }
        return tenant;
    }

    // ============================= Internal lookups (booking-service) ===================

    /**
     * The {@code ACTIVE} Tenants covering a location for a category, nearest first
     * (Requirements MT-1.4, MT-4.1, Property MT2): haversine distance from the Tenant's base at most
     * its radius, inclusive.
     *
     * @throws ProviderException 400 {@code VALIDATION_ERROR} for out-of-range coordinates
     */
    public List<CoveringTenant> covering(double lat, double lon, UUID categoryId) {
        if (!(lat >= -90.0 && lat <= 90.0) || !(lon >= -180.0 && lon <= 180.0)) {
            throw ProviderException.validation("lat must be within [-90, 90] and lon within [-180, 180]");
        }
        if (categoryId == null) {
            throw ProviderException.validation("categoryId is required");
        }
        // No Tenant reaches further than the maximum radius, so this box holds every candidate.
        GeoMath.BoundingBox box = GeoMath.boundingBox(lat, lon, MAX_RADIUS_KM.doubleValue());
        List<CoveringTenant> covering = new ArrayList<>();
        for (Tenant t : tenants.findCoverageCandidates(TenantStatus.ACTIVE, categoryId,
                box.minLat(), box.maxLat(), box.minLon(), box.maxLon())) {
            double distanceKm = GeoMath.haversineKm(lat, lon, t.getBaseLatitude(), t.getBaseLongitude());
            if (distanceKm <= t.getServiceRadiusKm().doubleValue()) {
                covering.add(new CoveringTenant(t.getId(), t.getName(), distanceKm));
            }
        }
        covering.sort(Comparator.comparingDouble(CoveringTenant::distanceKm)
                .thenComparing(c -> c.tenantId().toString()));
        return covering;
    }

    /** The Tenant {@code userId} administers, whatever its status. */
    public Optional<Tenant> byAdmin(UUID userId) {
        return admins.findById(userId).flatMap(a -> tenants.findById(a.getTenantId()));
    }

    /** The Tenant whose team {@code providerId} is on; empty for an independent or unknown provider. */
    public Optional<Tenant> ofProvider(UUID providerId) {
        return profiles.findById(providerId)
                .map(ProviderProfile::getTenantId)
                .flatMap(tenants::findById);
    }

    public Optional<Tenant> find(UUID tenantId) {
        return tenants.findById(tenantId);
    }

    // ============================= Validation (Requirement MT-1.2) ======================

    /**
     * Checks every rule of Requirement MT-1.2 and normalises the values. Each failure is a 400 whose
     * errorCode names the rule: {@code INVALID_TENANT_NAME}, {@code INVALID_CONTACT_PHONE},
     * {@code INVALID_CONTACT_EMAIL}, {@code INVALID_LATITUDE}, {@code INVALID_LONGITUDE},
     * {@code INVALID_SERVICE_RADIUS}, {@code CATEGORIES_REQUIRED}, {@code INACTIVE_CATEGORY}.
     */
    TenantDetails validate(TenantCommand command) {
        String name = command.name() == null ? "" : command.name().strip();
        if (name.length() < MIN_NAME_LENGTH || name.length() > MAX_NAME_LENGTH) {
            throw invalid("INVALID_TENANT_NAME",
                    "name must be " + MIN_NAME_LENGTH + "-" + MAX_NAME_LENGTH + " characters");
        }
        String phone = blankToNull(command.contactPhone());
        if (phone != null && (phone.length() > MAX_PHONE_LENGTH || !PHONE.matcher(phone).matches())) {
            throw invalid("INVALID_CONTACT_PHONE",
                    "contactPhone must be a phone number of at most " + MAX_PHONE_LENGTH + " characters");
        }
        String email = blankToNull(command.contactEmail());
        if (email != null && (email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches())) {
            throw invalid("INVALID_CONTACT_EMAIL", "contactEmail must be an email address");
        }
        Double lat = command.baseLatitude();
        if (lat == null || !(lat >= -90.0 && lat <= 90.0)) {
            throw invalid("INVALID_LATITUDE", "baseLatitude must be within [-90, 90]");
        }
        Double lon = command.baseLongitude();
        if (lon == null || !(lon >= -180.0 && lon <= 180.0)) {
            throw invalid("INVALID_LONGITUDE", "baseLongitude must be within [-180, 180]");
        }
        BigDecimal radius = command.serviceRadiusKm();
        if (radius == null || radius.compareTo(MIN_RADIUS_KM) < 0 || radius.compareTo(MAX_RADIUS_KM) > 0) {
            throw invalid("INVALID_SERVICE_RADIUS", "serviceRadiusKm must be within [1, 100]");
        }
        // The column holds one decimal; any value inside [1, 100] stays inside after rounding.
        BigDecimal scaledRadius = radius.setScale(1, RoundingMode.HALF_UP);
        if (command.categoryIds() == null || command.categoryIds().isEmpty()
                || command.categoryIds().stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid("CATEGORIES_REQUIRED", "categoryIds must list at least one category");
        }
        Set<UUID> categories = new LinkedHashSet<>(command.categoryIds());
        List<UUID> inactive = categories.stream().filter(id -> !catalog.isCategoryActive(id)).toList();
        if (!inactive.isEmpty()) {
            throw new ProviderException(HttpStatus.BAD_REQUEST, "INACTIVE_CATEGORY",
                    "every category must be an active service category",
                    inactive.stream().map(id -> "categoryIds: " + id + " is not an active category").toList());
        }
        return new TenantDetails(name, phone, email, lat, lon, scaledRadius, categories);
    }

    private static TenantStatus parseStatus(String status) {
        try {
            return TenantStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("INVALID_TENANT_STATUS", "status must be ACTIVE or SUSPENDED");
        }
    }

    /**
     * Strips the separators people type inside a number; the Auth Service stores E.164, so no
     * country code is guessed.
     */
    static String normaliseMobile(String mobileNumber) {
        String number = mobileNumber == null ? "" : mobileNumber.replaceAll("[\\s\\-()]", "");
        if (number.isEmpty()) {
            throw ProviderException.validation("mobileNumber is required");
        }
        return number;
    }

    // ----------------------------------------------------------------------------------------

    Tenant requireTenant(UUID tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> new ProviderException(
                HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Tenant " + tenantId + " not found"));
    }

    private TenantView view(Tenant tenant) {
        return new TenantView(tenant, profiles.countByTenantId(tenant.getId()),
                admins.countByTenantId(tenant.getId()));
    }

    private static Map<UUID, Long> toMap(List<TenantCount> counts) {
        return counts.stream().collect(Collectors.toMap(TenantCount::tenantId, TenantCount::count));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ProviderException invalid(String errorCode, String message) {
        return new ProviderException(HttpStatus.BAD_REQUEST, errorCode, message);
    }
}
