package com.homefix.provider.service;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.homefix.provider.auth.AuthUserClientPort;
import com.homefix.provider.domain.Tenant;
import com.homefix.provider.domain.Tenant.TenantDetails;
import com.homefix.provider.domain.TenantAdminRepository;
import com.homefix.provider.domain.TenantRepository;
import com.homefix.provider.domain.TenantStatus;
import com.homefix.provider.service.TenantViews.TenantView;

/**
 * Agency self-registration (email-auth Requirement 5; Property EA4).
 *
 * <p>Any signed-in user may apply once: the agency is recorded as a Tenant in
 * {@code PENDING_APPROVAL} with the applicant as its pending administrator. Nothing is granted yet:
 * the Tenant covers no booking and the applicant holds no {@code TENANT_ADMIN} until a
 * Platform_Admin approves. Approval reuses the existing add-administrator path (grant, then record
 * the membership) before the Tenant becomes {@code ACTIVE}; if activation then fails, the
 * administrator is removed again, so no half-approved state is left. Rejection records the reason the
 * applicant sees. Both decisions email the applicant through the Auth Service.
 */
@Service
public class TenantApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TenantApplicationService.class);

    static final int MAX_REASON_LENGTH = 500;

    private final TenantRepository tenants;
    private final TenantAdminRepository admins;
    private final TenantService tenantService;
    private final AuthUserClientPort auth;

    public TenantApplicationService(TenantRepository tenants, TenantAdminRepository admins,
                                    TenantService tenantService, AuthUserClientPort auth) {
        this.tenants = tenants;
        this.admins = admins;
        this.tenantService = tenantService;
        this.auth = auth;
    }

    /**
     * Records an agency application.
     *
     * @throws ProviderException 409 {@code APPLICATION_EXISTS} if the user already has a pending or
     *         approved application or administers a Tenant; 400 naming a failed rule, as for a Tenant
     *         a Platform_Admin creates (Requirement MT-1.2)
     */
    public TenantView apply(UUID applicantId, TenantCommand command) {
        if (admins.findById(applicantId).isPresent()
                || tenants.existsByApplicantUserIdAndStatusIn(applicantId,
                        EnumSet.of(TenantStatus.PENDING_APPROVAL, TenantStatus.ACTIVE))) {
            throw applicationExists();
        }
        TenantDetails details = tenantService.validate(command);
        Tenant saved;
        try {
            saved = tenants.saveAndFlush(Tenant.apply(details, applicantId));
        } catch (DataIntegrityViolationException e) {
            // A concurrent application by the same user won the unique index.
            throw applicationExists();
        }
        log.info("Agency application {} submitted by {} ({} categories)", saved.getId(), applicantId,
                saved.getCategoryIds().size());
        return new TenantView(saved, 0, 0);
    }

    /**
     * The applicant's latest application, whatever its status.
     *
     * @throws ProviderException 404 {@code APPLICATION_NOT_FOUND}
     */
    public Tenant mine(UUID applicantId) {
        return tenants.findFirstByApplicantUserIdOrderByCreatedAtDesc(applicantId)
                .orElseThrow(() -> new ProviderException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND",
                        "You have not applied to register an agency"));
    }

    /** Applications waiting for a decision, by name. */
    public List<TenantView> pending() {
        return tenantService.list().stream()
                .filter(view -> view.tenant().isPendingApproval())
                .toList();
    }

    /**
     * Approves an application: the applicant becomes the Tenant's administrator, then the Tenant
     * becomes {@code ACTIVE}, then the applicant is emailed.
     *
     * @throws ProviderException 404 {@code TENANT_NOT_FOUND}, 409 {@code APPLICATION_NOT_PENDING} /
     *         {@code ADMIN_OF_OTHER_TENANT}, 503 {@code AUTH_UNAVAILABLE} (nothing changed)
     */
    public TenantView approve(UUID tenantId, UUID actor) {
        Tenant tenant = requirePending(tenantId);
        UUID applicant = tenant.getApplicantUserId();
        tenantService.addAdminUser(tenantId, applicant, null, actor);
        try {
            tenant.approve(actor);
            tenant = tenants.saveAndFlush(tenant);
        } catch (RuntimeException e) {
            log.error("Agency application {} could not be activated; removing its new administrator {}",
                    tenantId, applicant, e);
            try {
                tenantService.removeAdmin(tenantId, applicant, actor);
            } catch (RuntimeException removeFailure) {
                log.error("Agency application {}: administrator {} could not be removed after the failed "
                        + "activation and holds TENANT_ADMIN for a pending Tenant", tenantId, applicant,
                        removeFailure);
            }
            throw e;
        }
        log.info("Agency application {} approved by {}; {} is its administrator", tenantId, actor, applicant);
        auth.sendAgencyDecision(applicant, tenant.getName(), true, null);
        return tenantService.get(tenantId);
    }

    /**
     * Rejects an application with a reason the applicant will see.
     *
     * @throws ProviderException 400 {@code REASON_REQUIRED}, 404 {@code TENANT_NOT_FOUND}, 409
     *         {@code APPLICATION_NOT_PENDING}
     */
    public TenantView reject(UUID tenantId, String rawReason, UUID actor) {
        String reason = rawReason == null ? "" : rawReason.strip();
        if (reason.isEmpty() || reason.length() > MAX_REASON_LENGTH) {
            throw new ProviderException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED",
                    "Give a reason of at most " + MAX_REASON_LENGTH + " characters");
        }
        Tenant tenant = requirePending(tenantId);
        tenant.reject(reason, actor);
        tenant = tenants.saveAndFlush(tenant);
        log.info("Agency application {} rejected by {}", tenantId, actor);
        auth.sendAgencyDecision(tenant.getApplicantUserId(), tenant.getName(), false, reason);
        return tenantService.get(tenantId);
    }

    private Tenant requirePending(UUID tenantId) {
        Tenant tenant = tenantService.requireTenant(tenantId);
        if (!tenant.isPendingApproval() || tenant.getApplicantUserId() == null) {
            throw new ProviderException(HttpStatus.CONFLICT, "APPLICATION_NOT_PENDING",
                    "This Tenant is not an agency application waiting for a decision");
        }
        return tenant;
    }

    private static ProviderException applicationExists() {
        return new ProviderException(HttpStatus.CONFLICT, "APPLICATION_EXISTS",
                "You already have an agency application or administer an agency");
    }
}
