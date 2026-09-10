package com.homefix.customer.api;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.homefix.customer.domain.CustomerProfile;
import com.homefix.customer.domain.DeletionRequest;
import com.homefix.customer.service.CustomerProfileService;
import com.homefix.customer.service.CustomerProfileService.DetectedLocation;

import jakarta.validation.Valid;

/**
 * Customer profile, GPS location detection, and data-deletion endpoints (Requirement 2.1,
 * 2.5, 26.8, 26.9).
 */
@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerProfileService service;

    public CustomerController(CustomerProfileService service) {
        this.service = service;
    }

    /**
     * {@code PUT /customers/{id}/profile} — update display name, email, and profile photo.
     *
     * <p>Consumes multipart so the JPEG/PNG photo (≤ 5 MB) can be validated and uploaded
     * alongside the JSON profile fields. The photo bytes would be streamed to S3 by a
     * storage adapter in a full deployment; here we validate and record the resulting URL.
     */
    @PutMapping(path = "/{id}/profile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProfileResponse> updateProfile(
            @PathVariable("id") UUID customerId,
            @Valid @RequestPart("profile") ProfileUpdateRequest request,
            @RequestPart(name = "photo", required = false) MultipartFile photo) {

        ProfilePhotoValidator.validate(photo);
        String photoUrl = request.photoUrl();
        CustomerProfile profile = service.updateProfile(
                customerId, request.displayName(), request.email(), photoUrl);
        return ResponseEntity.ok(ProfileResponse.from(profile));
    }

    /**
     * {@code POST /customers/{id}/location/detect} — resolve service location from device
     * GPS (Requirement 2.5).
     */
    @PostMapping("/{id}/location/detect")
    public ResponseEntity<DetectedLocation> detectLocation(
            @PathVariable("id") UUID customerId,
            @RequestBody LocationDetectionRequest request) {
        DetectedLocation location = service.detectLocation(
                request.lat(), request.lng(), request.gpsDenied());
        return ResponseEntity.ok(location);
    }

    /**
     * {@code POST /customers/{id}/deletion} — request account/data deletion. Acknowledged
     * immediately, well within the 24-hour SLA; anonymization is scheduled within 30 days
     * (Requirement 26.8, 26.9).
     */
    @PostMapping("/{id}/deletion")
    public ResponseEntity<Map<String, Object>> requestDeletion(@PathVariable("id") UUID customerId) {
        DeletionRequest request = service.requestDeletion(customerId);
        Map<String, Object> body = Map.of(
                "requestId", request.getId(),
                "status", request.getStatus().name(),
                "acknowledgedAt", request.getAcknowledgedAt(),
                "anonymizeBy", request.getAnonymizeAfter());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    /** Response projection for a profile update (does not expose PII ciphertext). */
    public record ProfileResponse(UUID id, UUID userId, String photoUrl) {
        static ProfileResponse from(CustomerProfile profile) {
            return new ProfileResponse(profile.getId(), profile.getUserId(), profile.getPhotoUrl());
        }
    }
}
