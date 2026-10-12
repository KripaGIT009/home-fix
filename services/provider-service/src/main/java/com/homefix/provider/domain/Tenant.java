package com.homefix.provider.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A service agency inside the one HomeFix marketplace (Requirement MT-1.1): a team of Providers
 * that covers a circular Service_Area for a set of Service_Categories.
 *
 * <p>Membership is not held here. Administrators live in {@link TenantAdmin} and Providers carry the
 * Tenant on their own profile ({@code provider_profile.tenant_id}), so each "at most one Tenant"
 * rule is a single-column fact the database enforces (Property MT8).
 *
 * <p>Range validation (Requirement MT-1.2) belongs to {@code TenantService}, which reports which
 * rule failed; the entity only keeps itself consistent. Every change goes through
 * {@link #update}, which stamps the acting Platform_Admin (Requirement MT-1.3), and the
 * {@link Version} column makes a concurrent edit fail instead of being silently lost.
 */
@Entity
@Table(name = "tenant")
public class Tenant {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TenantStatus status;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "contact_email", length = 254)
    private String contactEmail;

    @Column(name = "base_latitude", nullable = false)
    private double baseLatitude;

    @Column(name = "base_longitude", nullable = false)
    private double baseLongitude;

    @Column(name = "service_radius_km", nullable = false, precision = 5, scale = 1)
    private BigDecimal serviceRadiusKm;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "tenant_category", joinColumns = @JoinColumn(name = "tenant_id"))
    @Column(name = "category_id", nullable = false)
    private Set<UUID> categoryIds = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /** Null until first persisted, which is how Spring Data tells a new Tenant from an edit. */
    /** The account that applied for this agency; null for a Tenant a Platform_Admin created. */
    @Column(name = "applicant_user_id", updatable = false)
    private UUID applicantUserId;

    /** Why the application was rejected; shown to the applicant. */
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Tenant() {
        // JPA
    }

    /** A new {@code ACTIVE} Tenant created by {@code actor}. */
    public static Tenant create(TenantDetails details, UUID actor) {
        Tenant tenant = new Tenant();
        tenant.id = UUID.randomUUID();
        tenant.createdAt = Instant.now();
        tenant.status = TenantStatus.ACTIVE;
        tenant.update(details, TenantStatus.ACTIVE, actor);
        return tenant;
    }

    /**
     * An agency's own application (email-auth Requirement 5.2): {@code PENDING_APPROVAL}, so it covers
     * nothing and grants nothing until a Platform_Admin approves it.
     */
    public static Tenant apply(TenantDetails details, UUID applicantUserId) {
        Tenant tenant = new Tenant();
        tenant.id = UUID.randomUUID();
        tenant.createdAt = Instant.now();
        tenant.applicantUserId = applicantUserId;
        tenant.update(details, TenantStatus.PENDING_APPROVAL, null);
        return tenant;
    }

    /** A Platform_Admin's approval: the agency becomes {@code ACTIVE}. */
    public void approve(UUID actor) {
        requirePending();
        this.status = TenantStatus.ACTIVE;
        this.rejectionReason = null;
        this.updatedBy = actor;
        this.updatedAt = Instant.now();
    }

    /** A Platform_Admin's rejection, with the reason the applicant will see. */
    public void reject(String reason, UUID actor) {
        requirePending();
        this.status = TenantStatus.REJECTED;
        this.rejectionReason = reason;
        this.updatedBy = actor;
        this.updatedAt = Instant.now();
    }

    /** Puts an approval back when its admin could not be recorded, so nothing half-done stays ACTIVE. */
    public void revertApproval() {
        this.status = TenantStatus.PENDING_APPROVAL;
    }

    public boolean isPendingApproval() {
        return status == TenantStatus.PENDING_APPROVAL;
    }

    private void requirePending() {
        if (status != TenantStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Tenant " + id + " is " + status + ", not PENDING_APPROVAL");
        }
    }

    public UUID getApplicantUserId() {
        return applicantUserId;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    /**
     * Replaces the details, area, categories and status in one step (Requirement MT-1.3) and records
     * {@code actor} as the last Platform_Admin to change the Tenant.
     */
    public void update(TenantDetails details, TenantStatus newStatus, UUID actor) {
        this.name = details.name();
        this.contactPhone = details.contactPhone();
        this.contactEmail = details.contactEmail();
        this.baseLatitude = details.baseLatitude();
        this.baseLongitude = details.baseLongitude();
        this.serviceRadiusKm = details.serviceRadiusKm();
        replaceCategories(details.categoryIds());
        this.status = newStatus;
        this.updatedBy = actor;
        this.updatedAt = Instant.now();
    }

    private void replaceCategories(Collection<UUID> ids) {
        // Mutate the managed collection rather than swapping it, so Hibernate diffs the rows.
        this.categoryIds.retainAll(ids);
        this.categoryIds.addAll(ids);
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public double getBaseLatitude() {
        return baseLatitude;
    }

    public double getBaseLongitude() {
        return baseLongitude;
    }

    public BigDecimal getServiceRadiusKm() {
        return serviceRadiusKm;
    }

    public Set<UUID> getCategoryIds() {
        return categoryIds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    /**
     * The validated, normalised fields a Platform_Admin sets on a Tenant.
     *
     * @param serviceRadiusKm already scaled to one decimal place, within [1, 100]
     * @param categoryIds     non-empty, distinct, every id an active category
     */
    public record TenantDetails(String name, String contactPhone, String contactEmail,
                                double baseLatitude, double baseLongitude, BigDecimal serviceRadiusKm,
                                Set<UUID> categoryIds) {
    }
}
