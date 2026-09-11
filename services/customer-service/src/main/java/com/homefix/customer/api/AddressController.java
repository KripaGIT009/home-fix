package com.homefix.customer.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.customer.service.AddressResult;
import com.homefix.customer.service.CustomerProfileService;

import jakarta.validation.Valid;

/**
 * Saved-address endpoints (Requirement 2.2, 2.3, 2.4, 2.6).
 */
@RestController
@RequestMapping("/customers/{id}/addresses")
public class AddressController {

    private final CustomerProfileService service;
    private final CallerIdentity callerIdentity;

    public AddressController(CustomerProfileService service, CallerIdentity callerIdentity) {
        this.service = service;
        this.callerIdentity = callerIdentity;
    }

    /**
     * {@code POST /customers/{id}/addresses} — add an address from GPS coordinates. The
     * response carries a warning when reverse-geocoding failed (Requirement 2.2) and is
     * rejected when the 10-address limit is reached (Requirement 2.3).
     */
    @PostMapping
    public ResponseEntity<AddressResult> addAddress(
            @PathVariable("id") UUID customerId,
            @Valid @RequestBody AddressRequest request) {
        callerIdentity.requireSelfOrStaff(customerId);
        AddressResult result = service.addAddress(
                customerId, request.label(), request.lat(), request.lng());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * {@code DELETE /customers/{id}/addresses/{addressId}} — delete a saved address.
     * Rejected with 409 when referenced by an active booking (Requirement 2.6); promotes
     * the most recent remaining address to default when the default is deleted
     * (Requirement 2.4).
     */
    @DeleteMapping("/{addressId}")
    public ResponseEntity<Void> deleteAddress(
            @PathVariable("id") UUID customerId,
            @PathVariable("addressId") UUID addressId) {
        callerIdentity.requireSelfOrStaff(customerId);
        service.deleteAddress(customerId, addressId);
        return ResponseEntity.noContent().build();
    }
}
