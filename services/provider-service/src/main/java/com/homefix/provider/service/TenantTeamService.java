package com.homefix.provider.service;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.homefix.provider.auth.AuthUserClientPort;
import com.homefix.provider.auth.AuthUserClientPort.AuthUser;
import com.homefix.provider.config.ProviderProperties;
import com.homefix.provider.domain.ProviderProfile;
import com.homefix.provider.domain.ProviderProfileRepository;
import com.homefix.provider.domain.TenantRepository;
import com.homefix.provider.eligibility.EligibilityEvaluator;
import com.homefix.provider.service.TenantViews.MembershipView;
import com.homefix.provider.service.TenantViews.TeamProviderView;
import com.homefix.provider.verification.VerificationAdminClientPort;

/**
 * A Tenant's team of Providers (Requirement MT-3): adding and removing members by mobile number,
 * listing the team with the availability and verification a Tenant_Admin assigns by
 * (Requirements MT-3.4, MT-8.4), and the assignability check booking-service makes before a
 * Tenant assignment (Requirement MT-5.2, Property MT4).
 *
 * <p>Every operation is scoped by a Tenant id the caller has already resolved — from the path for
 * Platform_Admins, from the caller's identity for Tenant_Admins — and the membership statements
 * themselves carry that Tenant id, so a Tenant can never see, release or claim another Tenant's
 * provider (Requirement MT-10.2, Property MT5).
 *
 * <p>Membership does not touch automatic matching (Requirement MT-3.5): the eligibility search
 * never reads {@code tenant_id}.
 *
 * <p><b>Assignable</b> = member of the Tenant, verification {@code APPROVED}, and not under review.
 * The verification status comes from the same batch call the Admin provider list uses; when the
 * Verification Service cannot answer, the status is unknown and nobody is assignable — an
 * unverified provider must never be assigned because a dependency was down.
 */
@Service
public class TenantTeamService {

    private static final Logger log = LoggerFactory.getLogger(TenantTeamService.class);

    static final String APPROVED = "APPROVED";

    private final ProviderProfileRepository profiles;
    private final TenantRepository tenants;
    private final AuthUserClientPort auth;
    private final VerificationAdminClientPort verification;
    private final ProviderProperties props;
    private final Clock clock;

    @Autowired
    public TenantTeamService(ProviderProfileRepository profiles, TenantRepository tenants,
                             AuthUserClientPort auth, VerificationAdminClientPort verification,
                             ProviderProperties props) {
        this(profiles, tenants, auth, verification, props, Clock.systemUTC());
    }

    /** Fixed-clock constructor so tests can place "now" inside or outside a slot. */
    TenantTeamService(ProviderProfileRepository profiles, TenantRepository tenants,
                      AuthUserClientPort auth, VerificationAdminClientPort verification,
                      ProviderProperties props, Clock clock) {
        this.profiles = profiles;
        this.tenants = tenants;
        this.auth = auth;
        this.verification = verification;
        this.props = props;
        this.clock = clock;
    }

    /**
     * The Tenant's team by display name, with one batch verification lookup
     * (Requirements MT-3.4, MT-8.4). {@code mobileNumber} is null: it lives in the Auth Service and
     * a lookup per member is not worth it for a list.
     */
    public List<TeamProviderView> team(UUID tenantId) {
        List<ProviderProfile> members = profiles.findByTenantId(tenantId);
        if (members.isEmpty()) {
            return List.of();
        }
        Optional<Map<UUID, String>> statuses =
                verification.statusesOf(members.stream().map(ProviderProfile::getId).toList());
        ZonedDateTime now = now();
        return members.stream()
                .map(p -> view(p, null, statuses.map(m -> m.get(p.getId())).orElse(null), now))
                .toList();
    }

    /**
     * Attaches the provider registered with {@code mobileNumber} to the Tenant
     * (Requirement MT-3.2). Adding a provider already on this Tenant's team succeeds unchanged.
     *
     * @throws ProviderException 404 {@code TENANT_NOT_FOUND}; 404 {@code PROVIDER_NOT_FOUND} when no
     *         account has that number, it lacks {@code SERVICE_PROVIDER}, or it has no provider
     *         profile; 409 {@code PROVIDER_IN_OTHER_TENANT}; 503 {@code AUTH_UNAVAILABLE}
     */
    public TeamProviderView addProvider(UUID tenantId, String mobileNumber, UUID actor) {
        requireTenantExists(tenantId);
        String number = TenantService.normaliseMobile(mobileNumber);
        ProviderProfile profile = auth.findByMobile(number)
                .filter(user -> user.hasRole(AuthUserClientPort.SERVICE_PROVIDER))
                .map(AuthUser::userId)
                .flatMap(profiles::findByUserId)
                .orElseThrow(() -> ProviderException.notFound(
                        "No provider with a profile is registered with that mobile number"));
        UUID providerId = profile.getId();

        if (profiles.attachToTenant(providerId, tenantId) == 1) {
            log.info("Tenant {} provider {} added by {}", tenantId, providerId, actor);
        } else {
            UUID current = profiles.findById(providerId).map(ProviderProfile::getTenantId).orElse(null);
            if (!tenantId.equals(current)) {
                throw new ProviderException(HttpStatus.CONFLICT, "PROVIDER_IN_OTHER_TENANT",
                        "That provider already belongs to another Tenant");
            }
        }
        ProviderProfile member = profiles.findById(providerId).orElseThrow(() -> ProviderException.notFound(
                "Provider " + providerId + " not found"));
        Optional<Map<UUID, String>> statuses = verification.statusesOf(List.of(providerId));
        return view(member, number, statuses.map(m -> m.get(providerId)).orElse(null), now());
    }

    /**
     * Detaches a provider from the Tenant (Requirement MT-3.3). Bookings already assigned to the
     * provider are booking-service's and stay as they are.
     *
     * @throws ProviderException 404 {@code TENANT_NOT_FOUND}; 404 {@code PROVIDER_NOT_FOUND} when
     *         the provider is not on this Tenant's team — indistinguishable from an unknown one
     */
    public void removeProvider(UUID tenantId, UUID providerId, UUID actor) {
        requireTenantExists(tenantId);
        if (profiles.detachFromTenant(providerId, tenantId) == 0) {
            throw ProviderException.notFound("Provider " + providerId + " is not on this Tenant's team");
        }
        log.info("Tenant {} provider {} removed by {}", tenantId, providerId, actor);
    }

    /**
     * The provider's standing on the Tenant's team for booking-service's assignment check
     * (Requirement MT-5.2, Property MT4).
     *
     * @return empty when the provider is not a member of that Tenant (or either is unknown)
     */
    public Optional<MembershipView> membership(UUID tenantId, UUID providerId) {
        Optional<ProviderProfile> member = profiles.findById(providerId)
                .filter(p -> tenantId.equals(p.getTenantId()));
        if (member.isEmpty()) {
            return Optional.empty();
        }
        String status = verification.statusesOf(List.of(providerId))
                .map(m -> m.get(providerId))
                .orElse(null);
        return Optional.of(new MembershipView(true, assignable(member.get(), status), status));
    }

    // ----------------------------------------------------------------------------------------

    private TeamProviderView view(ProviderProfile p, String mobileNumber, String verificationStatus,
                                  ZonedDateTime now) {
        List<String> tags = p.getSkillTags();
        return new TeamProviderView(
                p.getId(),
                p.getDisplayName(),
                mobileNumber,
                tags.isEmpty() ? null : tags.get(0),
                verificationStatus,
                p.getAggregateRating(),
                EligibilityEvaluator.availabilityScore(p.getAvailabilitySlots(), now).isPresent(),
                assignable(p, verificationStatus));
    }

    private static boolean assignable(ProviderProfile p, String verificationStatus) {
        return p.getTenantId() != null && APPROVED.equals(verificationStatus) && !p.isUnderReview();
    }

    /** Now in the zone the weekly slots are written in, as the dispatch search reads them. */
    private ZonedDateTime now() {
        return ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of(props.getAvailabilityZone()));
    }

    private void requireTenantExists(UUID tenantId) {
        if (!tenants.existsById(tenantId)) {
            throw new ProviderException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND",
                    "Tenant " + tenantId + " not found");
        }
    }
}
