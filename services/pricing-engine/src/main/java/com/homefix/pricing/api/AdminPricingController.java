package com.homefix.pricing.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.pricing.api.dto.PricingParametersDto;
import com.homefix.pricing.service.PricingConfigService;

import jakarta.validation.Valid;

/**
 * Admin pricing-parameter configuration endpoints (Requirement 6.11).
 *
 * <p>Updates persist through the source of truth and invalidate the read-through cache so new
 * bookings pick up the change within the cache TTL (default 60 s). These endpoints require an
 * authenticated ADMIN principal; role enforcement is performed by the shared
 * {@code RbacEnforcementFilter} (Task 4).
 */
@RestController
@RequestMapping("/admin/pricing")
public class AdminPricingController {

    private final PricingConfigService configService;

    public AdminPricingController(PricingConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/parameters/{subcategoryId}")
    public ResponseEntity<PricingParametersDto> getParameters(
            @PathVariable("subcategoryId") UUID subcategoryId) {
        return ResponseEntity.ok(
                PricingParametersDto.from(configService.requireParameters(subcategoryId)));
    }

    @PutMapping("/parameters")
    public ResponseEntity<PricingParametersDto> updateParameters(
            @Valid @RequestBody PricingParametersDto request) {
        return ResponseEntity.ok(
                PricingParametersDto.from(configService.updateParameters(request.toDomain())));
    }
}
