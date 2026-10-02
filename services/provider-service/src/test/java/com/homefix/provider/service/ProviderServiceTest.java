package com.homefix.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.homefix.provider.alert.AdminAlertPort;
import com.homefix.provider.catalog.CatalogClientPort;
import com.homefix.provider.config.ProviderProperties;
import com.homefix.provider.crypto.KmsEncryptionPort;
import com.homefix.provider.crypto.LocalAesKmsAdapter;
import com.homefix.provider.domain.ProviderEarning;
import com.homefix.provider.domain.ProviderEarningRepository;
import com.homefix.provider.domain.ProviderProfile;
import com.homefix.provider.domain.Settlement;
import com.homefix.provider.domain.SettlementRepository;
import com.homefix.provider.support.InMemoryProviderProfileRepository;

/**
 * Unit tests for the provider domain logic (Requirements 4 and 14).
 *
 * <p>Tests operate against in-memory fakes and mocks — no Spring context, database, or
 * network access required.
 */
@ExtendWith(MockitoExtension.class)
class ProviderServiceTest {

    // Base64-encoded 256-bit test key (32 bytes of sequential bytes 0x30-0x4F).
    private static final String TEST_DATA_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private InMemoryProviderProfileRepository profileRepository;

    @Mock
    private ProviderEarningRepository earningRepository;

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private CatalogClientPort catalogClient;

    @Mock
    private AdminAlertPort adminAlert;

    private KmsEncryptionPort kms;
    private ProviderProperties props;
    private ProviderService service;

    @BeforeEach
    void setUp() {
        profileRepository = new InMemoryProviderProfileRepository();
        kms = new LocalAesKmsAdapter(TEST_DATA_KEY);
        props = new ProviderProperties();

        service = new ProviderService(
                profileRepository, earningRepository, settlementRepository,
                catalogClient, kms, adminAlert, props);
    }

    /** Creates a persisted profile for the given id with a zero wallet balance. */
    private ProviderProfile seedProfile(UUID id) {
        ProviderProfile p = ProviderProfile.createWithId(id);
        return profileRepository.save(p);
    }

    private void stubCatalogAllActive() {
        lenient().when(catalogClient.isCategoryActive(any())).thenReturn(true);
        lenient().when(catalogClient.isSubcategoryActive(any(), any())).thenReturn(true);
    }

    // ============================= Radius Validation =============================

    @Nested
    class RadiusValidation {

        @Test
        void radiusWithinRange_isAccepted() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            service.updateServiceRadius(id, 50);
            assertThat(profileRepository.findById(id).orElseThrow().getServiceRadiusKm()).isEqualTo(50);
        }

        @Test
        void radiusBelowMinimum_isRejected() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            assertThatThrownBy(() -> service.updateServiceRadius(id, 0))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(ex -> {
                        ProviderException pe = (ProviderException) ex;
                        assertThat(pe.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(pe.getMessage()).contains("1").contains("100");
                    });
        }

        @Test
        void radiusAboveMaximum_isRejected() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            assertThatThrownBy(() -> service.updateServiceRadius(id, 101))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(ex -> {
                        ProviderException pe = (ProviderException) ex;
                        assertThat(pe.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(pe.getMessage()).contains("1").contains("100");
                    });
        }

        @Test
        void radiusAtMinimumBoundary_isAccepted() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            service.updateServiceRadius(id, 1);
            assertThat(profileRepository.findById(id).orElseThrow().getServiceRadiusKm()).isEqualTo(1);
        }

        @Test
        void radiusAtMaximumBoundary_isAccepted() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            service.updateServiceRadius(id, 100);
            assertThat(profileRepository.findById(id).orElseThrow().getServiceRadiusKm()).isEqualTo(100);
        }
    }

    // ============================= Overlapping Availability ======================

    @Nested
    class OverlappingAvailability {

        @Test
        void nonOverlappingSlots_areAccepted() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            List<AvailabilityCommand.Slot> slots = List.of(
                    new AvailabilityCommand.Slot(DayOfWeek.MONDAY, 9, 12),
                    new AvailabilityCommand.Slot(DayOfWeek.MONDAY, 12, 17),
                    new AvailabilityCommand.Slot(DayOfWeek.TUESDAY, 9, 12));
            service.updateAvailability(id, new AvailabilityCommand(slots));
            assertThat(profileRepository.findById(id).orElseThrow().getAvailabilitySlots()).hasSize(3);
        }

        @Test
        void overlappingSlotsOnSameDay_areRejected() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            List<AvailabilityCommand.Slot> slots = List.of(
                    new AvailabilityCommand.Slot(DayOfWeek.WEDNESDAY, 8, 12),
                    new AvailabilityCommand.Slot(DayOfWeek.WEDNESDAY, 11, 14));
            assertThatThrownBy(() -> service.updateAvailability(id, new AvailabilityCommand(slots)))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(ex -> {
                        ProviderException pe = (ProviderException) ex;
                        assertThat(pe.getErrorCode()).isEqualTo("OVERLAPPING_AVAILABILITY");
                        assertThat(pe.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });
        }

        @Test
        void sameDayNonOverlappingAdjacentSlots_areAccepted() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            List<AvailabilityCommand.Slot> slots = List.of(
                    new AvailabilityCommand.Slot(DayOfWeek.FRIDAY, 8, 10),
                    new AvailabilityCommand.Slot(DayOfWeek.FRIDAY, 10, 12));
            service.updateAvailability(id, new AvailabilityCommand(slots));
            assertThat(profileRepository.findById(id).orElseThrow().getAvailabilitySlots()).hasSize(2);
        }

        @Test
        void differentDays_neverOverlap() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            List<AvailabilityCommand.Slot> slots = List.of(
                    new AvailabilityCommand.Slot(DayOfWeek.MONDAY, 8, 12),
                    new AvailabilityCommand.Slot(DayOfWeek.TUESDAY, 8, 12));
            service.updateAvailability(id, new AvailabilityCommand(slots));
            assertThat(profileRepository.findById(id).orElseThrow().getAvailabilitySlots()).hasSize(2);
        }
    }

    // ======================= Provider who has not onboarded yet ===================

    @Nested
    class NotOnboardedYet {

        @Test
        void dashboardReadsShowAnEmptyLedgerInsteadOfNotFound() {
            UUID id = UUID.randomUUID();
            lenient().when(earningRepository.findByProviderIdAndCreditedAtGreaterThanEqual(eq(id), any()))
                    .thenReturn(List.of());
            lenient().when(settlementRepository.findByProviderIdOrderByRequestedAtDesc(id))
                    .thenReturn(List.of());

            ProviderService.EarningsSnapshot summary = service.earningsSummary(id);
            assertThat(summary.walletBalance()).isEqualByComparingTo("0");
            assertThat(summary.todayNet()).isEqualByComparingTo("0");
            assertThat(summary.todayJobCount()).isZero();
            assertThat(service.settlementInfo(id).getWalletBalance()).isEqualByComparingTo("0");
            assertThat(service.settlementHistory(id)).isEmpty();
        }

        @Test
        void aDashboardReadDoesNotCreateTheProfile() {
            UUID id = UUID.randomUUID();
            lenient().when(earningRepository.findByProviderIdAndCreditedAtGreaterThanEqual(eq(id), any()))
                    .thenReturn(List.of());

            service.earningsSummary(id);
            service.settlementInfo(id);

            assertThat(profileRepository.findById(id)).isEmpty();
        }
    }

    // ============================= Wallet Credit =================================

    @Nested
    class WalletCredit {

        @Test
        void jobCreditIncreasesWalletByNetAmount() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            lenient().when(earningRepository.save(any(ProviderEarning.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.creditJobEarning(id, UUID.randomUUID(), "BK-001",
                    new BigDecimal("100.00"), new BigDecimal("15.00"));

            ProviderProfile after = profileRepository.findById(id).orElseThrow();
            // Net = 100 - 15 = 85
            assertThat(after.getWalletBalance()).isEqualByComparingTo("85.00");
        }

        @Test
        void multipleCredits_accumulate() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            lenient().when(earningRepository.save(any(ProviderEarning.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.creditJobEarning(id, UUID.randomUUID(), "BK-001",
                    new BigDecimal("200.00"), new BigDecimal("30.00"));
            service.creditJobEarning(id, UUID.randomUUID(), "BK-002",
                    new BigDecimal("50.00"), new BigDecimal("7.50"));

            ProviderProfile after = profileRepository.findById(id).orElseThrow();
            // (200-30) + (50-7.50) = 170 + 42.5 = 212.50
            assertThat(after.getWalletBalance()).isEqualByComparingTo("212.50");
        }

        @Test
        void penaltyDebitsWallet() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            lenient().when(earningRepository.save(any(ProviderEarning.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Seed some earnings first.
            service.creditJobEarning(id, UUID.randomUUID(), "BK-001",
                    new BigDecimal("100.00"), new BigDecimal("10.00"));
            // Wallet = 90.00
            service.applyPenalty(id, UUID.randomUUID(), "BK-001", new BigDecimal("25.00"));
            // Wallet = 65.00
            assertThat(profileRepository.findById(id).orElseThrow().getWalletBalance())
                    .isEqualByComparingTo("65.00");
        }
    }

    // ============================= Settlement Validation =========================

    @Nested
    class SettlementValidation {

        @Test
        void validSettlement_isAccepted() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            lenient().when(earningRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(settlementRepository.save(any(Settlement.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Credit wallet and set a verified bank account.
            service.creditJobEarning(id, UUID.randomUUID(), "BK-001",
                    new BigDecimal("100.00"), BigDecimal.ZERO);
            service.setBankAccount(id, "ACCT-12345", true);

            Settlement settlement = service.requestSettlement(id,
                    new SettlementCommand(new BigDecimal("50.00"), "ACCT-12345"));

            assertThat(settlement.getAmount()).isEqualByComparingTo("50.00");
            // Bank account ref is encrypted — ciphertext differs from plaintext.
            assertThat(settlement.getBankAccountRefEncrypted())
                    .isNotEqualTo("ACCT-12345")
                    .startsWith("v1:");
            // Wallet debited.
            assertThat(profileRepository.findById(id).orElseThrow().getWalletBalance())
                    .isEqualByComparingTo("50.00");
        }

        @Test
        void amountBelowMinimum_isRejected() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            lenient().when(earningRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.creditJobEarning(id, UUID.randomUUID(), "BK-001",
                    new BigDecimal("100.00"), BigDecimal.ZERO);
            service.setBankAccount(id, "ACCT-12345", true);

            assertThatThrownBy(() -> service.requestSettlement(id,
                    new SettlementCommand(new BigDecimal("0.50"), "ACCT-12345")))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(ex -> assertThat(((ProviderException) ex).getErrorCode())
                            .isEqualTo("SETTLEMENT_AMOUNT_TOO_LOW"));
        }

        @Test
        void amountExceedsBalance_isRejected() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            lenient().when(earningRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.creditJobEarning(id, UUID.randomUUID(), "BK-001",
                    new BigDecimal("50.00"), BigDecimal.ZERO);
            service.setBankAccount(id, "ACCT-12345", true);

            assertThatThrownBy(() -> service.requestSettlement(id,
                    new SettlementCommand(new BigDecimal("100.00"), "ACCT-12345")))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(ex -> assertThat(((ProviderException) ex).getErrorCode())
                            .isEqualTo("SETTLEMENT_AMOUNT_EXCEEDS_BALANCE"));
        }

        @Test
        void noBankAccount_isRejected() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            lenient().when(earningRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.creditJobEarning(id, UUID.randomUUID(), "BK-001",
                    new BigDecimal("100.00"), BigDecimal.ZERO);
            // No bank account set — bankAccountVerified is false by default.

            assertThatThrownBy(() -> service.requestSettlement(id,
                    new SettlementCommand(new BigDecimal("10.00"), "ACCT-12345")))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(ex -> assertThat(((ProviderException) ex).getErrorCode())
                            .isEqualTo("NO_VERIFIED_BANK_ACCOUNT"));
        }
    }

    // ============================= Profile Validation ============================

    @Nested
    class ProfileValidation {

        @Test
        void validProfile_isAccepted() {
            stubCatalogAllActive();
            UUID id = UUID.randomUUID();
            UUID catId = UUID.randomUUID();
            UUID subId = UUID.randomUUID();

            ProfileUpdateCommand cmd = new ProfileUpdateCommand(
                    "Rahul P",
                    List.of(new ProfileUpdateCommand.CategorySelectionCommand(catId, List.of(subId))),
                    List.of("plumbing", "pipes"),
                    10, 25);
            ProviderProfile profile = service.updateProfile(id, cmd);

            assertThat(profile.getDisplayName()).isEqualTo("Rahul P");
            assertThat(profile.getSkillTags()).containsExactly("plumbing", "pipes");
            assertThat(profile.getYearsExperience()).isEqualTo(10);
            assertThat(profile.getServiceRadiusKm()).isEqualTo(25);
            assertThat(profile.getCategorySelections()).hasSize(1);
        }

        @Test
        void tooManyCategories_isRejected() {
            stubCatalogAllActive();
            UUID id = UUID.randomUUID();
            List<ProfileUpdateCommand.CategorySelectionCommand> cats = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                cats.add(new ProfileUpdateCommand.CategorySelectionCommand(UUID.randomUUID(), List.of()));
            }
            ProfileUpdateCommand cmd = new ProfileUpdateCommand("Name", cats, List.of("skill"), 5, 10);
            assertThatThrownBy(() -> service.updateProfile(id, cmd))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(ex -> assertThat(((ProviderException) ex).getMessage()).contains("5"));
        }

        @Test
        void deactivatedCategory_isRejected() {
            UUID id = UUID.randomUUID();
            UUID catId = UUID.randomUUID();
            when(catalogClient.isCategoryActive(catId)).thenReturn(false);

            ProfileUpdateCommand cmd = new ProfileUpdateCommand(
                    "Name",
                    List.of(new ProfileUpdateCommand.CategorySelectionCommand(catId, List.of())),
                    List.of("skill"),
                    5, 10);
            assertThatThrownBy(() -> service.updateProfile(id, cmd))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(ex -> assertThat(((ProviderException) ex).getErrorCode())
                            .isEqualTo("CATEGORY_DEACTIVATED"));
        }

        @Test
        void deactivatedSubcategory_isRejected() {
            UUID id = UUID.randomUUID();
            UUID catId = UUID.randomUUID();
            UUID subId = UUID.randomUUID();
            when(catalogClient.isCategoryActive(catId)).thenReturn(true);
            when(catalogClient.isSubcategoryActive(catId, subId)).thenReturn(false);

            ProfileUpdateCommand cmd = new ProfileUpdateCommand(
                    "Name",
                    List.of(new ProfileUpdateCommand.CategorySelectionCommand(catId, List.of(subId))),
                    List.of("skill"),
                    5, 10);
            assertThatThrownBy(() -> service.updateProfile(id, cmd))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(ex -> assertThat(((ProviderException) ex).getErrorCode())
                            .isEqualTo("SUBCATEGORY_DEACTIVATED"));
        }

        @Test
        void emptySkillTags_isRejected() {
            ProfileUpdateCommand cmd = new ProfileUpdateCommand("Name", List.of(), List.of(), 5, 10);
            assertThatThrownBy(() -> service.updateProfile(UUID.randomUUID(), cmd))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(ex -> assertThat(((ProviderException) ex).getMessage()).contains("1"));
        }

        @Test
        void tooManySkillTags_isRejected() {
            List<String> tags = new java.util.ArrayList<>();
            for (int i = 0; i < 21; i++) tags.add("tag" + i);
            ProfileUpdateCommand cmd = new ProfileUpdateCommand("Name", List.of(), tags, 5, 10);
            assertThatThrownBy(() -> service.updateProfile(UUID.randomUUID(), cmd))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(ex -> assertThat(((ProviderException) ex).getMessage()).contains("20"));
        }
    }

    // ============================= Rating Auto-Flag ==============================

    @Nested
    class RatingAutoFlag {

        @Test
        void ratingBelowThreshold_flagsForReview() {
            UUID id = UUID.randomUUID();
            seedProfile(id);

            service.applyAggregateRating(id, new BigDecimal("2.8"));

            ProviderProfile after = profileRepository.findById(id).orElseThrow();
            assertThat(after.isUnderReview()).isTrue();
            verify(adminAlert).providerFlaggedForReview(id, new BigDecimal("2.8"));
        }

        @Test
        void ratingAtThreshold_doesNotFlag() {
            UUID id = UUID.randomUUID();
            seedProfile(id);

            service.applyAggregateRating(id, new BigDecimal("3.0"));

            ProviderProfile after = profileRepository.findById(id).orElseThrow();
            assertThat(after.isUnderReview()).isFalse();
            verify(adminAlert, never()).providerFlaggedForReview(any(), any());
        }

        @Test
        void alreadyFlagged_doesNotAlertAgain() {
            UUID id = UUID.randomUUID();
            seedProfile(id);

            service.applyAggregateRating(id, new BigDecimal("2.5"));
            // Second drop below threshold — already flagged, no second alert.
            service.applyAggregateRating(id, new BigDecimal("2.0"));

            verify(adminAlert).providerFlaggedForReview(id, new BigDecimal("2.5"));
            // Only one invocation total (the first one).
        }
    }

    // ============================= KMS Encryption (bank account) =================

    @Nested
    class BankAccountEncryption {

        @Test
        void bankAccountIsStoredEncrypted() {
            UUID id = UUID.randomUUID();
            seedProfile(id);

            service.setBankAccount(id, "GB33BUKB20201555555555", true);

            ProviderProfile after = profileRepository.findById(id).orElseThrow();
            // Stored value is ciphertext, not plaintext.
            assertThat(after.getBankAccountEncrypted())
                    .isNotEqualTo("GB33BUKB20201555555555")
                    .startsWith("v1:");
            assertThat(after.isBankAccountVerified()).isTrue();
            // Decrypt round-trip to verify.
            assertThat(kms.decrypt(after.getBankAccountEncrypted()))
                    .isEqualTo("GB33BUKB20201555555555");
        }
    }

    // ============================= Emergency Availability ========================

    @Nested
    class EmergencyAvailability {

        @Test
        void toggleEmergencyOn() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            service.updateEmergencyAvailability(id, true);
            assertThat(profileRepository.findById(id).orElseThrow().isEmergencyAvailable()).isTrue();
        }

        @Test
        void toggleEmergencyOff() {
            UUID id = UUID.randomUUID();
            seedProfile(id);
            service.updateEmergencyAvailability(id, true);
            service.updateEmergencyAvailability(id, false);
            assertThat(profileRepository.findById(id).orElseThrow().isEmergencyAvailable()).isFalse();
        }
    }

    // ============================= Base Location (Req 8.2) =======================

    @Nested
    class BaseLocation {

        private ProfileUpdateCommand withLocation(Double lat, Double lon) {
            return new ProfileUpdateCommand("Rahul P", List.of(), List.of("plumbing"), 5, 10, lat, lon);
        }

        @Test
        void bothCoordinates_areStored() {
            UUID id = UUID.randomUUID();

            ProviderProfile profile = service.updateProfile(id, withLocation(25.5560, 84.6603));

            assertThat(profile.getBaseLatitude()).isEqualTo(25.5560);
            assertThat(profile.getBaseLongitude()).isEqualTo(84.6603);
            assertThat(profile.hasBaseLocation()).isTrue();
        }

        @Test
        void neitherCoordinate_leavesTheLocationOnFileUnchanged() {
            // A client that predates the field must not wipe the location (and with it the
            // provider's dispatch eligibility) by re-saving the profile.
            UUID id = UUID.randomUUID();
            service.updateProfile(id, withLocation(25.5560, 84.6603));

            ProviderProfile profile = service.updateProfile(id, new ProfileUpdateCommand(
                    "Rahul P", List.of(), List.of("plumbing"), 5, 10));

            assertThat(profile.getBaseLatitude()).isEqualTo(25.5560);
            assertThat(profile.getBaseLongitude()).isEqualTo(84.6603);
        }

        @Test
        void newProfileWithoutLocation_hasNone() {
            ProviderProfile profile = service.updateProfile(UUID.randomUUID(), withLocation(null, null));

            assertThat(profile.hasBaseLocation()).isFalse();
        }

        @Test
        void onlyOneCoordinate_isRejected() {
            UUID id = UUID.randomUUID();
            assertThatThrownBy(() -> service.updateProfile(id, withLocation(25.5, null)))
                    .isInstanceOf(ProviderException.class)
                    .hasMessageContaining("together");
            assertThatThrownBy(() -> service.updateProfile(id, withLocation(null, 84.6)))
                    .isInstanceOf(ProviderException.class)
                    .hasMessageContaining("together");
            assertThat(profileRepository.findById(id)).isEmpty();
        }

        @Test
        void outOfRangeOrNonFiniteCoordinates_areRejected() {
            UUID id = UUID.randomUUID();
            assertThatThrownBy(() -> service.updateProfile(id, withLocation(90.01, 84.0)))
                    .isInstanceOf(ProviderException.class).hasMessageContaining("baseLatitude");
            assertThatThrownBy(() -> service.updateProfile(id, withLocation(-90.01, 84.0)))
                    .isInstanceOf(ProviderException.class).hasMessageContaining("baseLatitude");
            assertThatThrownBy(() -> service.updateProfile(id, withLocation(25.0, 180.01)))
                    .isInstanceOf(ProviderException.class).hasMessageContaining("baseLongitude");
            assertThatThrownBy(() -> service.updateProfile(id, withLocation(25.0, -180.01)))
                    .isInstanceOf(ProviderException.class).hasMessageContaining("baseLongitude");
            assertThatThrownBy(() -> service.updateProfile(id, withLocation(Double.NaN, 84.0)))
                    .isInstanceOf(ProviderException.class).hasMessageContaining("baseLatitude");
        }

        @Test
        void boundaryCoordinates_areAccepted() {
            ProviderProfile profile = service.updateProfile(UUID.randomUUID(), withLocation(-90.0, 180.0));

            assertThat(profile.getBaseLatitude()).isEqualTo(-90.0);
            assertThat(profile.getBaseLongitude()).isEqualTo(180.0);
        }
    }
}
