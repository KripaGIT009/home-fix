package com.homefix.provider.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.provider.api.dto.EligibleProvidersResponse;
import com.homefix.provider.api.dto.ProviderSummariesResponse;
import com.homefix.provider.eligibility.EligibilityQuery;
import com.homefix.provider.eligibility.ProviderEligibilityService;
import com.homefix.provider.service.ProviderAdminService;

/**
 * Service-to-service read surface for the Dispatch Engine's eligibility search (Requirement 8.2)
 * and the Verification Service's Admin review queue (Requirement 19.3).
 *
 * <p>Reachable only with the shared {@code X-Internal-Api-Key} credential — see
 * {@code InternalApiKeyFilter} and {@code WebSecurityConfig}. The API Gateway routes only
 * {@code /providers/**} here, so {@code /internal/**} is not exposed to clients, and no RBAC rule
 * covers it: the caller is a service, not a user with roles.
 */
@RestController
@RequestMapping("/internal/providers")
public class InternalProviderController {

    private final ProviderEligibilityService eligibilityService;
    private final ProviderAdminService adminService;

    public InternalProviderController(ProviderEligibilityService eligibilityService,
                                      ProviderAdminService adminService) {
        this.eligibilityService = eligibilityService;
        this.adminService = adminService;
    }

    /**
     * {@code GET /internal/providers/eligible} — the providers eligible for a booking, with their
     * Matching_Score components (Requirements 8.2, 8.3).
     *
     * <p>{@code skillTags} is repeated ({@code skillTags=a&skillTags=b}); a provider qualifies with
     * any one of them. {@code subcategoryId} is accepted for the contract but is not a filter. An
     * empty {@code providers} list is a normal answer, not an error.
     */
    @GetMapping("/eligible")
    public ResponseEntity<EligibleProvidersResponse> eligible(
            @RequestParam(name = "subcategoryId", required = false) UUID subcategoryId,
            @RequestParam("lat") double lat,
            @RequestParam("lon") double lon,
            @RequestParam("radiusKm") double radiusKm,
            @RequestParam(name = "emergency", defaultValue = "false") boolean emergency,
            @RequestParam(name = "skillTags", required = false) List<String> skillTags) {
        EligibilityQuery query = new EligibilityQuery(subcategoryId, lat, lon, radiusKm, emergency, skillTags);
        return ResponseEntity.ok(EligibleProvidersResponse.from(eligibilityService.findEligible(query)));
    }

    /**
     * {@code GET /internal/providers/summaries?ids=a&ids=b} — display name and primary skill of
     * each given provider, for the Verification Service's Admin review queue (Requirement 19.3).
     *
     * <p>One call per queue listing. At most {@value ProviderAdminService#MAX_SUMMARY_IDS} ids
     * (400 {@code VALIDATION_ERROR} beyond that); unknown ids are absent from the answer. It
     * conveys nothing a reviewer cannot already see on the provider's profile.
     */
    @GetMapping("/summaries")
    public ResponseEntity<ProviderSummariesResponse> summaries(
            @RequestParam(name = "ids", required = false) List<UUID> ids) {
        return ResponseEntity.ok(ProviderSummariesResponse.from(adminService.summaries(ids)));
    }
}
