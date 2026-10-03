package com.homefix.provider.api;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.provider.api.dto.ActiveJobResponse;
import com.homefix.provider.api.dto.AvailabilityRequest;
import com.homefix.provider.api.dto.BankAccountRequest;
import com.homefix.provider.api.dto.BankAccountResponse;
import com.homefix.provider.bank.BankAccountView;
import com.homefix.provider.api.dto.EarningsSummaryResponse;
import com.homefix.provider.api.dto.EarningResponse;
import com.homefix.provider.api.dto.EmergencyAvailabilityRequest;
import com.homefix.provider.api.dto.ProfileRequest;
import com.homefix.provider.api.dto.ProfileResponse;
import com.homefix.provider.api.dto.RadiusRequest;
import com.homefix.provider.api.dto.SettlementRequestDto;
import com.homefix.provider.api.dto.SettlementInfoResponse;
import com.homefix.provider.api.dto.SettlementResponse;
import com.homefix.provider.domain.ProviderProfile;
import com.homefix.provider.domain.Settlement;
import com.homefix.provider.service.AvailabilityCommand;
import com.homefix.provider.service.BankAccountCommand;
import com.homefix.provider.service.ProfileUpdateCommand;
import com.homefix.provider.booking.BookingClientPort;
import com.homefix.provider.booking.CatalogSubcategoryNames;
import com.homefix.provider.config.ProviderProperties;
import com.homefix.provider.service.ProviderException;
import com.homefix.provider.service.ProviderService;
import com.homefix.provider.service.SettlementCommand;

import jakarta.validation.Valid;

/**
 * Provider profile, availability, wallet, and settlement endpoints (Requirements 4 and 14).
 *
 * <p>Coarse role enforcement is applied by the shared {@code RbacEnforcementFilter} from the rules
 * in {@code ProviderRbacConfig}. Because every path here carries a {@code {id}} provider
 * identifier, a role check alone is not sufficient — each handler additionally asserts ownership
 * through {@link CallerIdentity#requireSelfOrStaff(UUID)} as its first statement, so a provider
 * cannot read or mutate another provider's record.
 */
@RestController
@RequestMapping("/providers/{id}")
public class ProviderController {

    /** Path segment a client may use instead of its own provider id. */
    private static final String SELF_ALIAS = "me";

    private final ProviderService providerService;
    private final CallerIdentity callerIdentity;
    private final ProviderProperties props;
    private final BookingClientPort bookingClient;
    private final CatalogSubcategoryNames subcategoryNames;

    public ProviderController(ProviderService providerService, CallerIdentity callerIdentity,
                              ProviderProperties props, BookingClientPort bookingClient,
                              CatalogSubcategoryNames subcategoryNames) {
        this.providerService = providerService;
        this.callerIdentity = callerIdentity;
        this.props = props;
        this.bookingClient = bookingClient;
        this.subcategoryNames = subcategoryNames;
    }

    /**
     * Resolves the {@code {id}} path segment, accepting the literal {@code me} as an alias for the
     * authenticated caller.
     *
     * <p>The provider app addresses its own resources as {@code /providers/me/...} so no screen has
     * to thread the account id through every call. {@code me} resolves to the JWT subject, which is
     * also the profile identity (see {@code ProviderProfile.createWithId}), so the ownership
     * assertion that follows is satisfied by construction rather than bypassed.
     *
     * @throws ProviderException 400 when the segment is neither {@code me} nor a UUID
     */
    private UUID resolveProviderId(String rawId) {
        if (SELF_ALIAS.equalsIgnoreCase(rawId)) {
            return callerIdentity.requireCallerId();
        }
        try {
            return UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            throw new ProviderException(HttpStatus.BAD_REQUEST, "INVALID_PROVIDER_ID",
                    "provider id must be a UUID or the literal '" + SELF_ALIAS + "'");
        }
    }

    /**
     * {@code PUT /providers/{id}/profile} — categories, skills, experience, radius (Req 4.1–4.3,
     * 4.8), and optionally the base service location dispatch measures distance from (Req 8.2).
     */
    @PutMapping("/profile")
    public ResponseEntity<ProfileResponse> updateProfile(@PathVariable("id") String rawId,
                                                         @Valid @RequestBody ProfileRequest request) {
        UUID id = resolveProviderId(rawId);
        callerIdentity.requireSelfOrStaff(id);
        List<ProfileUpdateCommand.CategorySelectionCommand> cats = request.categories().stream()
                .map(c -> new ProfileUpdateCommand.CategorySelectionCommand(
                        c.categoryId(),
                        c.subcategoryIds() == null ? List.of() : c.subcategoryIds()))
                .toList();
        ProviderProfile profile = providerService.updateProfile(id, new ProfileUpdateCommand(
                request.displayName(), cats, request.skillTags(),
                request.yearsExperience(), request.serviceRadiusKm(),
                request.baseLatitude(), request.baseLongitude()));
        return ResponseEntity.ok(ProfileResponse.from(profile));
    }

    /** {@code PUT /providers/{id}/radius} — update service radius (Req 4.4). */
    @PutMapping("/radius")
    public ResponseEntity<ProfileResponse> updateRadius(@PathVariable("id") String rawId,
                                                        @Valid @RequestBody RadiusRequest request) {
        UUID id = resolveProviderId(rawId);
        callerIdentity.requireSelfOrStaff(id);
        ProviderProfile profile = providerService.updateServiceRadius(id, request.serviceRadiusKm());
        return ResponseEntity.ok(ProfileResponse.from(profile));
    }

    /** {@code PUT /providers/{id}/availability} — general availability schedule (Req 4.5). */
    @PutMapping("/availability")
    public ResponseEntity<ProfileResponse> updateAvailability(@PathVariable("id") String rawId,
                                                             @Valid @RequestBody AvailabilityRequest request) {
        UUID id = resolveProviderId(rawId);
        callerIdentity.requireSelfOrStaff(id);
        List<AvailabilityCommand.Slot> slots = request.slots().stream()
                .map(s -> new AvailabilityCommand.Slot(s.dayOfWeek(), s.startHour(), s.endHour()))
                .toList();
        ProviderProfile profile = providerService.updateAvailability(id, new AvailabilityCommand(slots));
        return ResponseEntity.ok(ProfileResponse.from(profile));
    }

    /** {@code PUT /providers/{id}/emergency-availability} — toggle emergency flag (Req 4.6). */
    @PutMapping("/emergency-availability")
    public ResponseEntity<ProfileResponse> updateEmergencyAvailability(
            @PathVariable("id") String rawId,
            @Valid @RequestBody EmergencyAvailabilityRequest request) {
        UUID id = resolveProviderId(rawId);
        callerIdentity.requireSelfOrStaff(id);
        ProviderProfile profile = providerService.updateEmergencyAvailability(id, request.emergencyAvailable());
        return ResponseEntity.ok(ProfileResponse.from(profile));
    }

    /** {@code POST /providers/{id}/settlements} — request a settlement (Req 14.2, 4.9). */
    @PostMapping("/settlements")
    public ResponseEntity<SettlementResponse> requestSettlement(@PathVariable("id") String rawId,
                                                               @Valid @RequestBody SettlementRequestDto request) {
        UUID id = resolveProviderId(rawId);
        callerIdentity.requireSelfOrStaff(id);
        Settlement settlement = providerService.requestSettlement(id,
                new SettlementCommand(request.amount(), request.bankAccountRef()));
        return ResponseEntity.status(HttpStatus.CREATED).body(SettlementResponse.from(settlement));
    }

    /** {@code GET /providers/{id}/earnings} — paginated earnings history (Req 14.5). */
    @GetMapping("/earnings")
    public ResponseEntity<Page<EarningResponse>> earningsHistory(
            @PathVariable("id") String rawId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        UUID id = resolveProviderId(rawId);
        callerIdentity.requireSelfOrStaff(id);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<EarningResponse> result = providerService.earningsHistory(id, pageable)
                .map(EarningResponse::from);
        return ResponseEntity.ok(result);
    }

    /** {@code GET /providers/{id}/summary} — wallet balance and today's earnings (Req 14.1). */
    @GetMapping("/summary")
    public ResponseEntity<EarningsSummaryResponse> summary(@PathVariable("id") String rawId) {
        UUID id = resolveProviderId(rawId);
        callerIdentity.requireSelfOrStaff(id);
        ProviderService.EarningsSnapshot snapshot = providerService.earningsSummary(id);
        return ResponseEntity.ok(new EarningsSummaryResponse(
                snapshot.walletBalance(), snapshot.todayNet(), snapshot.todayJobCount(),
                props.getCurrency()));
    }

    /**
     * {@code GET /providers/{id}/active-jobs} — jobs the provider currently has in flight
     * (Requirement 28.8).
     *
     * <p>Assembled from the Booking Service's internal read surface, with service names resolved
     * from the catalog. Both lookups degrade to empty rather than failing the request, so a
     * transient outage downstream costs the provider the job list, not the whole dashboard.
     *
     * <p>{@code customerArea} is returned empty: the locality lives in the Customer Service and
     * that lookup is not wired yet.
     */
    @GetMapping("/active-jobs")
    public ResponseEntity<List<ActiveJobResponse>> activeJobs(@PathVariable("id") String rawId) {
        UUID id = resolveProviderId(rawId);
        callerIdentity.requireSelfOrStaff(id);
        List<ActiveJobResponse> jobs = bookingClient.activeJobs(id).stream()
                .map(job -> new ActiveJobResponse(
                        job.bookingId(),
                        job.reference(),
                        subcategoryNames.nameOf(job.subcategoryId()),
                        job.status(),
                        job.emergency(),
                        job.scheduledAt(),
                        "",
                        job.estimatedTotal()))
                .toList();
        return ResponseEntity.ok(jobs);
    }

    /** {@code GET /providers/{id}/settlement-info} — balance and bank accounts on file (Req 14.2). */
    @GetMapping("/settlement-info")
    public ResponseEntity<SettlementInfoResponse> settlementInfo(@PathVariable("id") String rawId) {
        UUID id = resolveProviderId(rawId);
        callerIdentity.requireSelfOrStaff(id);
        ProviderProfile profile = providerService.settlementInfo(id);
        List<BankAccountResponse> accounts = providerService.bankAccountOf(profile)
                .map(view -> List.of(BankAccountResponse.from(profile.getId(), view)))
                .orElse(List.of());
        return ResponseEntity.ok(new SettlementInfoResponse(
                profile.getWalletBalance(), props.getCurrency(), accounts));
    }

    /**
     * {@code PUT /providers/{id}/bank-account} — add or replace the settlement bank account
     * (Requirements 4.9, 14.2). Stored encrypted; answered masked. With the default manual
     * verification the account is pending until an administrator verifies it.
     */
    @PutMapping("/bank-account")
    public ResponseEntity<BankAccountResponse> setBankAccount(@PathVariable("id") String rawId,
                                                              @RequestBody BankAccountRequest request) {
        UUID id = resolveProviderId(rawId);
        callerIdentity.requireSelfOrStaff(id);
        BankAccountView view = providerService.setBankAccount(id, new BankAccountCommand(
                request.accountHolderName(), request.accountNumber(), request.ifsc()));
        return ResponseEntity.ok(BankAccountResponse.from(id, view));
    }

    /** {@code GET /providers/{id}/settlements} — settlement request history (Req 14.3). */
    @GetMapping("/settlements")
    public ResponseEntity<List<SettlementResponse>> settlementHistory(@PathVariable("id") String rawId) {
        UUID id = resolveProviderId(rawId);
        callerIdentity.requireSelfOrStaff(id);
        return ResponseEntity.ok(providerService.settlementHistory(id).stream()
                .map(SettlementResponse::from)
                .toList());
    }

    /** {@code GET /providers/{id}/profile} — read the current profile. */
    @GetMapping("/profile")
    public ResponseEntity<ProfileResponse> getProfile(@PathVariable("id") String rawId) {
        UUID id = resolveProviderId(rawId);
        callerIdentity.requireSelfOrStaff(id);
        return ResponseEntity.ok(ProfileResponse.from(providerService.getProfile(id)));
    }
}
