package com.homefix.provider.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Provider profile aggregate (Requirement 4, mirrors the {@code PROVIDER_PROFILE} data model).
 *
 * <p>Owns the provider's selected service categories/subcategories, skill tags, experience,
 * service radius, availability schedule, emergency-availability flag, aggregate rating,
 * review flag, wallet balance, and encrypted bank account reference.
 */
@Entity
@Table(name = "provider_profile")
public class ProviderProfile {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private UUID userId;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "years_experience", nullable = false)
    private int yearsExperience;

    @Column(name = "service_radius_km", nullable = false)
    private int serviceRadiusKm;

    @Column(name = "aggregate_rating", nullable = false)
    private BigDecimal aggregateRating = BigDecimal.ZERO;

    @Column(name = "wallet_balance", nullable = false, precision = 12, scale = 2)
    private BigDecimal walletBalance = BigDecimal.ZERO;

    @Column(name = "emergency_available", nullable = false)
    private boolean emergencyAvailable;

    @Column(name = "under_review", nullable = false)
    private boolean underReview;

    /**
     * Latitude of the provider's base service location, in decimal degrees (WGS84). Together with
     * {@link #baseLongitude} it is the point the service radius is measured from when dispatch
     * looks for eligible providers (Requirement 8.2).
     *
     * <p>Nullable because profiles created before the column existed, and providers who have not
     * yet set it, have no location; such a provider is never eligible for dispatch, since there is
     * nothing to measure a distance from. The pair is set and cleared together — see
     * {@link #setBaseLocation(Double, Double)}.
     */
    @Column(name = "base_latitude")
    private Double baseLatitude;

    /** Longitude of the base service location, in decimal degrees (WGS84). See {@link #baseLatitude}. */
    @Column(name = "base_longitude")
    private Double baseLongitude;

    /** AES-256/KMS ciphertext of the settlement bank account details (Requirement 4.9). */
    @Column(name = "bank_account_encrypted")
    private String bankAccountEncrypted;

    @Column(name = "bank_account_verified", nullable = false)
    private boolean bankAccountVerified;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "provider_skill_tag", joinColumns = @JoinColumn(name = "provider_id"))
    @Column(name = "tag", nullable = false, length = 64)
    private List<String> skillTags = new ArrayList<>();

    @OneToMany(mappedBy = "provider", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<ProviderCategorySelection> categorySelections = new ArrayList<>();

    @OneToMany(mappedBy = "provider", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<AvailabilitySlot> availabilitySlots = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected ProviderProfile() {
        // JPA
    }

    private ProviderProfile(UUID id, UUID userId) {
        this.id = id;
        this.userId = userId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Factory for a brand-new profile with sensible defaults. */
    public static ProviderProfile create(UUID userId) {
        return new ProviderProfile(UUID.randomUUID(), userId);
    }

    /**
     * Factory that pins the profile id to a caller-supplied value, used by the
     * {@code PUT /providers/{id}/profile} flow where {@code {id}} is the profile identity.
     * The same value is also used as the user id until a distinct account link is wired in.
     */
    public static ProviderProfile createWithId(UUID id) {
        return new ProviderProfile(id, id);
    }

    // ----- Selection replacement helpers (keep bidirectional links consistent) -----

    public void replaceSkillTags(List<String> tags) {
        this.skillTags = new ArrayList<>(tags);
        touch();
    }

    public void replaceCategorySelections(List<ProviderCategorySelection> selections) {
        this.categorySelections.clear();
        for (ProviderCategorySelection selection : selections) {
            selection.attachTo(this);
            this.categorySelections.add(selection);
        }
        touch();
    }

    public void replaceAvailability(List<AvailabilitySlot> slots) {
        this.availabilitySlots.clear();
        for (AvailabilitySlot slot : slots) {
            slot.attachTo(this);
            this.availabilitySlots.add(slot);
        }
        touch();
    }

    public void setServiceRadiusKm(int serviceRadiusKm) {
        this.serviceRadiusKm = serviceRadiusKm;
        touch();
    }

    public void setYearsExperience(int yearsExperience) {
        this.yearsExperience = yearsExperience;
        touch();
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        touch();
    }

    public void setEmergencyAvailable(boolean emergencyAvailable) {
        this.emergencyAvailable = emergencyAvailable;
        touch();
    }

    /**
     * Sets the base service location. Both coordinates are required together: a latitude without
     * a longitude is not a place. Range validation is the service layer's job; this guard only
     * keeps the pair consistent whatever the caller.
     */
    public void setBaseLocation(Double latitude, Double longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("base latitude and longitude must be set together");
        }
        this.baseLatitude = latitude;
        this.baseLongitude = longitude;
        touch();
    }

    public void setBankAccount(String bankAccountEncrypted, boolean verified) {
        this.bankAccountEncrypted = bankAccountEncrypted;
        this.bankAccountVerified = verified;
        touch();
    }

    /**
     * Credits net earnings to the wallet (Requirement 14.1). The caller is responsible for
     * recording the itemised {@link ProviderEarning} entry.
     */
    public void creditWallet(BigDecimal netAmount) {
        this.walletBalance = this.walletBalance.add(netAmount);
        touch();
    }

    /** Debits a settled amount from the wallet (Requirement 14.1). */
    public void debitWallet(BigDecimal amount) {
        this.walletBalance = this.walletBalance.subtract(amount);
        touch();
    }

    /**
     * Records a new aggregate rating and reports whether this transition should trigger an
     * Admin-review flag (Requirement 4.7): rating dropped below threshold and not already
     * flagged. Sets {@code underReview} as a side effect when triggered.
     *
     * @return {@code true} if the provider was newly flagged for review.
     */
    public boolean applyAggregateRating(BigDecimal newRating, BigDecimal threshold) {
        this.aggregateRating = newRating;
        touch();
        if (newRating.compareTo(threshold) < 0 && !this.underReview) {
            this.underReview = true;
            return true;
        }
        return false;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    // ----- Accessors -----

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getYearsExperience() {
        return yearsExperience;
    }

    public int getServiceRadiusKm() {
        return serviceRadiusKm;
    }

    public BigDecimal getAggregateRating() {
        return aggregateRating;
    }

    public BigDecimal getWalletBalance() {
        return walletBalance;
    }

    public boolean isEmergencyAvailable() {
        return emergencyAvailable;
    }

    public boolean isUnderReview() {
        return underReview;
    }

    public Double getBaseLatitude() {
        return baseLatitude;
    }

    public Double getBaseLongitude() {
        return baseLongitude;
    }

    /** {@code true} when a base service location is on file, the precondition for dispatch. */
    public boolean hasBaseLocation() {
        return baseLatitude != null && baseLongitude != null;
    }

    public String getBankAccountEncrypted() {
        return bankAccountEncrypted;
    }

    public boolean isBankAccountVerified() {
        return bankAccountVerified;
    }

    public List<String> getSkillTags() {
        return skillTags;
    }

    public List<ProviderCategorySelection> getCategorySelections() {
        return categorySelections;
    }

    public List<AvailabilitySlot> getAvailabilitySlots() {
        return availabilitySlots;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
