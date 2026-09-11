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

import com.homefix.provider.api.dto.AvailabilityRequest;
import com.homefix.provider.api.dto.EarningResponse;
import com.homefix.provider.api.dto.EmergencyAvailabilityRequest;
import com.homefix.provider.api.dto.ProfileRequest;
import com.homefix.provider.api.dto.ProfileResponse;
import com.homefix.provider.api.dto.RadiusRequest;
import com.homefix.provider.api.dto.SettlementRequestDto;
import com.homefix.provider.api.dto.SettlementResponse;
import com.homefix.provider.domain.ProviderProfile;
import com.homefix.provider.domain.Settlement;
import com.homefix.provider.service.AvailabilityCommand;
import com.homefix.provider.service.ProfileUpdateCommand;
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

    private final ProviderService providerService;
    private final CallerIdentity callerIdentity;

    public ProviderController(ProviderService providerService, CallerIdentity callerIdentity) {
        this.providerService = providerService;
        this.callerIdentity = callerIdentity;
    }

    /** {@code PUT /providers/{id}/profile} — categories, skills, experience, radius (Req 4.1–4.3, 4.8). */
    @PutMapping("/profile")
    public ResponseEntity<ProfileResponse> updateProfile(@PathVariable("id") UUID id,
                                                         @Valid @RequestBody ProfileRequest request) {
        callerIdentity.requireSelfOrStaff(id);
        List<ProfileUpdateCommand.CategorySelectionCommand> cats = request.categories().stream()
                .map(c -> new ProfileUpdateCommand.CategorySelectionCommand(
                        c.categoryId(),
                        c.subcategoryIds() == null ? List.of() : c.subcategoryIds()))
                .toList();
        ProviderProfile profile = providerService.updateProfile(id, new ProfileUpdateCommand(
                request.displayName(), cats, request.skillTags(),
                request.yearsExperience(), request.serviceRadiusKm()));
        return ResponseEntity.ok(ProfileResponse.from(profile));
    }

    /** {@code PUT /providers/{id}/radius} — update service radius (Req 4.4). */
    @PutMapping("/radius")
    public ResponseEntity<ProfileResponse> updateRadius(@PathVariable("id") UUID id,
                                                        @Valid @RequestBody RadiusRequest request) {
        callerIdentity.requireSelfOrStaff(id);
        ProviderProfile profile = providerService.updateServiceRadius(id, request.serviceRadiusKm());
        return ResponseEntity.ok(ProfileResponse.from(profile));
    }

    /** {@code PUT /providers/{id}/availability} — general availability schedule (Req 4.5). */
    @PutMapping("/availability")
    public ResponseEntity<ProfileResponse> updateAvailability(@PathVariable("id") UUID id,
                                                             @Valid @RequestBody AvailabilityRequest request) {
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
            @PathVariable("id") UUID id,
            @Valid @RequestBody EmergencyAvailabilityRequest request) {
        callerIdentity.requireSelfOrStaff(id);
        ProviderProfile profile = providerService.updateEmergencyAvailability(id, request.emergencyAvailable());
        return ResponseEntity.ok(ProfileResponse.from(profile));
    }

    /** {@code POST /providers/{id}/settlements} — request a settlement (Req 14.2, 4.9). */
    @PostMapping("/settlements")
    public ResponseEntity<SettlementResponse> requestSettlement(@PathVariable("id") UUID id,
                                                               @Valid @RequestBody SettlementRequestDto request) {
        callerIdentity.requireSelfOrStaff(id);
        Settlement settlement = providerService.requestSettlement(id,
                new SettlementCommand(request.amount(), request.bankAccountRef()));
        return ResponseEntity.status(HttpStatus.CREATED).body(SettlementResponse.from(settlement));
    }

    /** {@code GET /providers/{id}/earnings} — paginated earnings history (Req 14.5). */
    @GetMapping("/earnings")
    public ResponseEntity<Page<EarningResponse>> earningsHistory(
            @PathVariable("id") UUID id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        callerIdentity.requireSelfOrStaff(id);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<EarningResponse> result = providerService.earningsHistory(id, pageable)
                .map(EarningResponse::from);
        return ResponseEntity.ok(result);
    }

    /** {@code GET /providers/{id}/profile} — read the current profile. */
    @GetMapping("/profile")
    public ResponseEntity<ProfileResponse> getProfile(@PathVariable("id") UUID id) {
        callerIdentity.requireSelfOrStaff(id);
        return ResponseEntity.ok(ProfileResponse.from(providerService.getProfile(id)));
    }
}
