package com.homefix.provider.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.provider.alert.AdminAlertPort;
import com.homefix.provider.catalog.CatalogClientPort;
import com.homefix.provider.config.ProviderProperties;
import com.homefix.provider.crypto.KmsEncryptionPort;
import com.homefix.provider.domain.AvailabilitySlot;
import com.homefix.provider.domain.ProviderCategorySelection;
import com.homefix.provider.domain.EarningType;
import com.homefix.provider.domain.ProviderEarning;
import com.homefix.provider.domain.ProviderEarningRepository;
import com.homefix.provider.domain.ProviderProfile;
import com.homefix.provider.domain.ProviderProfileRepository;
import com.homefix.provider.domain.Settlement;
import com.homefix.provider.domain.SettlementRepository;
import com.homefix.provider.service.ProfileUpdateCommand.CategorySelectionCommand;

/**
 * Core provider-domain business logic: profile/skills/radius, availability schedule,
 * emergency-availability toggle, wallet balance, earnings history, settlement requests, and
 * rating-based Admin-review flagging (Requirements 4 and 14).
 *
 * <p>External dependencies are expressed as ports ({@link CatalogClientPort},
 * {@link KmsEncryptionPort}, {@link AdminAlertPort}) so the logic is fully unit-testable.
 */
@Service
public class ProviderService {

    private final ProviderProfileRepository profileRepository;
    private final ProviderEarningRepository earningRepository;
    private final SettlementRepository settlementRepository;
    private final CatalogClientPort catalogClient;
    private final KmsEncryptionPort kms;
    private final AdminAlertPort adminAlert;
    private final ProviderProperties props;

    public ProviderService(ProviderProfileRepository profileRepository,
                           ProviderEarningRepository earningRepository,
                           SettlementRepository settlementRepository,
                           CatalogClientPort catalogClient,
                           KmsEncryptionPort kms,
                           AdminAlertPort adminAlert,
                           ProviderProperties props) {
        this.profileRepository = profileRepository;
        this.earningRepository = earningRepository;
        this.settlementRepository = settlementRepository;
        this.catalogClient = catalogClient;
        this.kms = kms;
        this.adminAlert = adminAlert;
        this.props = props;
    }

    // ================= Profile (Requirement 4.1–4.3, 4.8) =================

    @Transactional
    public ProviderProfile updateProfile(UUID providerId, ProfileUpdateCommand cmd) {
        validateSkillTags(cmd.skillTags());
        validateYearsExperience(cmd.yearsExperience());
        validateServiceRadius(cmd.serviceRadiusKm());
        validateBaseLocation(cmd.baseLatitude(), cmd.baseLongitude());
        List<ProviderCategorySelection> selections = validateAndBuildSelections(cmd.categories());

        ProviderProfile profile = getOrCreate(providerId);
        if (cmd.baseLatitude() != null) {
            // Both-or-neither is validated above; neither means "keep the location on file".
            profile.setBaseLocation(cmd.baseLatitude(), cmd.baseLongitude());
        }
        profile.setDisplayName(cmd.displayName());
        profile.replaceSkillTags(cmd.skillTags());
        profile.setYearsExperience(cmd.yearsExperience());
        profile.setServiceRadiusKm(cmd.serviceRadiusKm());
        profile.replaceCategorySelections(selections);
        return profileRepository.save(profile);
    }

    private void validateSkillTags(List<String> tags) {
        int count = tags == null ? 0 : tags.size();
        if (count < props.getMinSkillTags() || count > props.getMaxSkillTags()) {
            throw ProviderException.validation(
                    "Skill tags must number between " + props.getMinSkillTags()
                            + " and " + props.getMaxSkillTags() + " (got " + count + ")");
        }
    }

    private void validateYearsExperience(int years) {
        if (years < props.getMinYearsExperience() || years > props.getMaxYearsExperience()) {
            throw ProviderException.validation(
                    "Years of experience must be between " + props.getMinYearsExperience()
                            + " and " + props.getMaxYearsExperience() + " (got " + years + ")");
        }
    }

    /** Requirement 4.2: radius outside 1–100 km is rejected with the permitted range. */
    private void validateServiceRadius(int radiusKm) {
        if (radiusKm < props.getMinServiceRadiusKm() || radiusKm > props.getMaxServiceRadiusKm()) {
            throw ProviderException.validation(
                    "Service radius must be between " + props.getMinServiceRadiusKm()
                            + " and " + props.getMaxServiceRadiusKm() + " kilometers (got " + radiusKm + ")");
        }
    }

    /**
     * Base service location (Requirement 8.2): both coordinates or neither, each finite and within
     * the WGS84 range. The DTO's bean-validation bounds cover the HTTP path; this is the
     * authoritative check for every caller of the service.
     */
    private void validateBaseLocation(Double latitude, Double longitude) {
        if (latitude == null && longitude == null) {
            return;
        }
        if (latitude == null || longitude == null) {
            throw ProviderException.validation(
                    "baseLatitude and baseLongitude must be provided together");
        }
        if (!Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0) {
            throw ProviderException.validation(
                    "baseLatitude must be between -90 and 90 (got " + latitude + ")");
        }
        if (!Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0) {
            throw ProviderException.validation(
                    "baseLongitude must be between -180 and 180 (got " + longitude + ")");
        }
    }

    private List<ProviderCategorySelection> validateAndBuildSelections(List<CategorySelectionCommand> categories) {
        List<CategorySelectionCommand> cats = categories == null ? List.of() : categories;
        if (cats.size() > props.getMaxActiveCategories()) {
            throw ProviderException.validation(
                    "A provider may select at most " + props.getMaxActiveCategories()
                            + " active service categories (got " + cats.size() + ")");
        }

        List<ProviderCategorySelection> result = new ArrayList<>();
        for (CategorySelectionCommand cat : cats) {
            List<UUID> subs = cat.subcategoryIds() == null ? List.of() : cat.subcategoryIds();
            if (subs.size() > props.getMaxSubcategoriesPerCategory()) {
                throw ProviderException.validation(
                        "Category " + cat.categoryId() + " has " + subs.size()
                                + " subcategories; the maximum per category is "
                                + props.getMaxSubcategoriesPerCategory());
            }
            // Requirement 4.3, 4.8: reject deactivated categories/subcategories via the catalog.
            if (!catalogClient.isCategoryActive(cat.categoryId())) {
                throw new ProviderException(HttpStatus.BAD_REQUEST, "CATEGORY_DEACTIVATED",
                        "Service category " + cat.categoryId() + " is deactivated or does not exist");
            }
            for (UUID sub : subs) {
                if (!catalogClient.isSubcategoryActive(cat.categoryId(), sub)) {
                    throw new ProviderException(HttpStatus.BAD_REQUEST, "SUBCATEGORY_DEACTIVATED",
                            "Service subcategory " + sub + " is deactivated or does not exist under category "
                                    + cat.categoryId());
                }
            }
            result.add(new ProviderCategorySelection(cat.categoryId(), subs));
        }
        return result;
    }

    // ================= Radius (Requirement 4.4) =================

    @Transactional
    public ProviderProfile updateServiceRadius(UUID providerId, int radiusKm) {
        validateServiceRadius(radiusKm);
        ProviderProfile profile = getExisting(providerId);
        profile.setServiceRadiusKm(radiusKm);
        return profileRepository.save(profile);
    }

    // ================= Emergency availability (Requirement 4.6) =================

    @Transactional
    public ProviderProfile updateEmergencyAvailability(UUID providerId, boolean available) {
        ProviderProfile profile = getExisting(providerId);
        profile.setEmergencyAvailable(available);
        return profileRepository.save(profile);
    }

    // ================= Availability schedule (Requirement 4.5) =================

    @Transactional
    public ProviderProfile updateAvailability(UUID providerId, AvailabilityCommand cmd) {
        List<AvailabilitySlot> slots = buildAndValidateSlots(cmd);
        ProviderProfile profile = getExisting(providerId);
        profile.replaceAvailability(slots);
        return profileRepository.save(profile);
    }

    private List<AvailabilitySlot> buildAndValidateSlots(AvailabilityCommand cmd) {
        List<AvailabilityCommand.Slot> input = cmd.slots() == null ? List.of() : cmd.slots();
        List<AvailabilitySlot> slots = new ArrayList<>();
        for (AvailabilityCommand.Slot s : input) {
            if (s.dayOfWeek() == null) {
                throw ProviderException.validation("Availability slot day-of-week is required");
            }
            if (s.startHour() < 0 || s.startHour() > 23) {
                throw ProviderException.validation(
                        "Availability slot startHour must be in [0,23] (got " + s.startHour() + ")");
            }
            if (s.endHour() < 1 || s.endHour() > 24) {
                throw ProviderException.validation(
                        "Availability slot endHour must be in [1,24] (got " + s.endHour() + ")");
            }
            if (s.endHour() <= s.startHour()) {
                throw ProviderException.validation(
                        "Availability slot endHour (" + s.endHour() + ") must be after startHour ("
                                + s.startHour() + ")");
            }
            slots.add(new AvailabilitySlot(s.dayOfWeek(), s.startHour(), s.endHour()));
        }
        // Requirement 4.5: reject overlapping slots.
        for (int i = 0; i < slots.size(); i++) {
            for (int j = i + 1; j < slots.size(); j++) {
                if (slots.get(i).overlaps(slots.get(j))) {
                    AvailabilitySlot a = slots.get(i);
                    AvailabilitySlot b = slots.get(j);
                    throw new ProviderException(HttpStatus.BAD_REQUEST, "OVERLAPPING_AVAILABILITY",
                            "Availability slots overlap on " + a.getDayOfWeek() + ": ["
                                    + a.getStartHour() + "," + a.getEndHour() + ") and ["
                                    + b.getStartHour() + "," + b.getEndHour() + ")");
                }
            }
        }
        return slots;
    }

    // ================= Wallet & earnings (Requirement 14.1, 14.5) =================

    /**
     * Credits a completed job's net earning to the wallet and records an itemised earnings
     * line showing gross, platform fee, and net (Requirement 14.1).
     */
    @Transactional
    public ProviderProfile creditJobEarning(UUID providerId, UUID bookingId, String bookingReference,
                                             BigDecimal gross, BigDecimal platformFee) {
        if (gross == null || gross.signum() < 0) {
            throw ProviderException.validation("Gross earning must be non-negative");
        }
        if (platformFee == null || platformFee.signum() < 0) {
            throw ProviderException.validation("Platform fee must be non-negative");
        }
        if (platformFee.compareTo(gross) > 0) {
            throw ProviderException.validation("Platform fee cannot exceed gross earning");
        }
        ProviderProfile profile = getExisting(providerId);
        ProviderEarning earning = ProviderEarning.jobCredit(providerId, bookingId, bookingReference, gross, platformFee);
        earningRepository.save(earning);
        profile.creditWallet(earning.getNet());
        return profileRepository.save(profile);
    }

    /** Records a complaint-related penalty deduction, itemised separately (Requirement 14.1). */
    @Transactional
    public ProviderProfile applyPenalty(UUID providerId, UUID bookingId, String bookingReference, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw ProviderException.validation("Penalty amount must be positive");
        }
        ProviderProfile profile = getExisting(providerId);
        earningRepository.save(ProviderEarning.penalty(providerId, bookingId, bookingReference, amount));
        profile.debitWallet(amount);
        return profileRepository.save(profile);
    }

    @Transactional(readOnly = true)
    public Page<ProviderEarning> earningsHistory(UUID providerId, Pageable pageable) {
        // No profile yet means no earnings yet: the ledger query simply finds nothing.
        return earningRepository.findByProviderIdOrderByCreditedAtDesc(providerId, pageable);
    }

    // ================= Settlement (Requirement 14.2, 4.9) =================

    @Transactional
    public Settlement requestSettlement(UUID providerId, SettlementCommand cmd) {
        ProviderProfile profile = getExisting(providerId);

        BigDecimal amount = cmd.amount();
        if (amount == null || amount.compareTo(props.getMinSettlementAmount()) < 0) {
            throw new ProviderException(HttpStatus.BAD_REQUEST, "SETTLEMENT_AMOUNT_TOO_LOW",
                    "Settlement amount must be at least " + props.getMinSettlementAmount());
        }
        if (amount.compareTo(profile.getWalletBalance()) > 0) {
            throw new ProviderException(HttpStatus.BAD_REQUEST, "SETTLEMENT_AMOUNT_EXCEEDS_BALANCE",
                    "Settlement amount " + amount + " exceeds available wallet balance "
                            + profile.getWalletBalance());
        }
        if (!profile.isBankAccountVerified()) {
            throw new ProviderException(HttpStatus.BAD_REQUEST, "NO_VERIFIED_BANK_ACCOUNT",
                    "A verified bank account is required before requesting a settlement");
        }

        // Requirement 4.9: bank account reference stored encrypted at rest.
        String encryptedRef = kms.encrypt(
                cmd.bankAccountRef() != null ? cmd.bankAccountRef() : profile.getBankAccountEncrypted());
        Settlement settlement = Settlement.request(providerId, amount, encryptedRef);
        settlementRepository.save(settlement);

        // Reserve the funds by debiting the wallet; a failed transfer credits them back downstream.
        profile.debitWallet(amount);
        profileRepository.save(profile);
        return settlement;
    }

    /**
     * Stores a provider's settlement bank account, encrypted at rest, and records whether it
     * is verified (Requirement 4.9).
     */
    @Transactional
    public ProviderProfile setBankAccount(UUID providerId, String bankAccountRef, boolean verified) {
        ProviderProfile profile = getExisting(providerId);
        profile.setBankAccount(kms.encrypt(bankAccountRef), verified);
        return profileRepository.save(profile);
    }

    // ================= Rating flag (Requirement 4.7) =================

    /**
     * Applies a recalculated aggregate rating; if it drops below the configured threshold and
     * the provider is not already flagged, flags them for Admin review and emits an alert.
     */
    @Transactional
    public ProviderProfile applyAggregateRating(UUID providerId, BigDecimal newRating) {
        ProviderProfile profile = getExisting(providerId);
        boolean newlyFlagged = profile.applyAggregateRating(newRating, props.getReviewRatingThreshold());
        profileRepository.save(profile);
        if (newlyFlagged) {
            adminAlert.providerFlaggedForReview(providerId, newRating);
        }
        return profile;
    }

    // ================= Reads =================

    @Transactional(readOnly = true)
    public ProviderProfile getProfile(UUID providerId) {
        return getExisting(providerId);
    }

    /**
     * Wallet balance plus the running total credited so far today (Requirement 14.1).
     *
     * <p>"Today" is the civil day in {@code homefix.provider.earnings-day-zone}, not a rolling
     * 24-hour window, so the figure resets at local midnight the way a provider expects. Only
     * {@link EarningType#JOB_CREDIT} rows count towards the job tally — fee and penalty rows are
     * itemised separately in the ledger and would otherwise inflate the count.
     */
    @Transactional(readOnly = true)
    public EarningsSnapshot earningsSummary(UUID providerId) {
        ProviderProfile profile = getOrEmpty(providerId);
        Instant dayStart = LocalDate.now(ZoneId.of(props.getEarningsDayZone()))
                .atStartOfDay(ZoneId.of(props.getEarningsDayZone()))
                .toInstant();
        List<ProviderEarning> today =
                earningRepository.findByProviderIdAndCreditedAtGreaterThanEqual(providerId, dayStart);
        BigDecimal net = today.stream()
                .map(ProviderEarning::getNet)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int jobs = (int) today.stream()
                .filter(e -> e.getType() == EarningType.JOB_CREDIT)
                .count();
        return new EarningsSnapshot(profile.getWalletBalance(), net, jobs);
    }

    /** Settlement request history, newest first (Requirement 14.3). */
    @Transactional(readOnly = true)
    public List<Settlement> settlementHistory(UUID providerId) {
        return settlementRepository.findByProviderIdOrderByRequestedAtDesc(providerId);
    }

    /**
     * Wallet balance and the bank account on file for settlements (Requirement 14.2).
     *
     * <p>The profile carries at most one account today, so the list is empty or a single entry;
     * returning a list keeps the contract stable once multiple accounts are supported.
     */
    @Transactional(readOnly = true)
    public ProviderProfile settlementInfo(UUID providerId) {
        return getOrEmpty(providerId);
    }

    /** Wallet balance and today's credited total, as returned by {@link #earningsSummary(UUID)}. */
    public record EarningsSnapshot(BigDecimal walletBalance, BigDecimal todayNet, int todayJobCount) {
    }

    private ProviderProfile getExisting(UUID providerId) {
        return profileRepository.findById(providerId)
                .orElseThrow(() -> ProviderException.notFound("Provider " + providerId + " not found"));
    }

    /**
     * The provider's profile for a dashboard read, or an unsaved blank one when they have not
     * completed onboarding yet. A freshly registered provider has a zero wallet and no history —
     * that is an empty ledger, not a missing provider — and answering 404 left the provider app's
     * dashboard showing "provider not found" with no way forward. Writes still require a real
     * profile ({@link #getExisting}).
     */
    private ProviderProfile getOrEmpty(UUID providerId) {
        return profileRepository.findById(providerId)
                .orElseGet(() -> ProviderProfile.createWithId(providerId));
    }

    private ProviderProfile getOrCreate(UUID providerId) {
        // PUT /providers/{id}/profile is upsert semantics: {id} is the profile identity.
        return profileRepository.findById(providerId)
                .orElseGet(() -> ProviderProfile.createWithId(providerId));
    }
}
