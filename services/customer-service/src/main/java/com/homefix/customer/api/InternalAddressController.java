package com.homefix.customer.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.customer.domain.AddressRepository;
import com.homefix.customer.service.CustomerException;

/**
 * Service-to-service lookups on saved addresses, under {@code /internal}.
 *
 * <p>Bookings reference an address by id and the {@code BookingCreated} event carries only that
 * id, so the Dispatch Engine resolves it here to the coordinates it searches for providers around
 * (Requirement 8.2). Keeping the lookup here, rather than denormalising coordinates into the event,
 * leaves the Customer Service the single owner of where a customer lives.
 *
 * <p>Not reachable by end users: {@code /internal/**} requires the shared service credential in
 * {@code X-Internal-Api-Key} (see {@code InternalApiKeyFilter}) and is not routed by the API
 * Gateway. The response omits the encrypted street address, and nothing here logs coordinates
 * (Requirement 26.4).
 */
@RestController
@RequestMapping("/internal/addresses")
public class InternalAddressController {

    private final AddressRepository addressRepository;

    public InternalAddressController(AddressRepository addressRepository) {
        this.addressRepository = addressRepository;
    }

    /**
     * {@code GET /internal/addresses/{addressId}} — an address's owner and coordinates.
     *
     * @return 200 with {@link InternalAddressResponse}; 404 {@code ADDRESS_NOT_FOUND} when no
     *         address has that id (never saved, or deleted by the customer)
     */
    @GetMapping("/{addressId}")
    public ResponseEntity<InternalAddressResponse> address(@PathVariable("addressId") UUID addressId) {
        return addressRepository.findById(addressId)
                .map(InternalAddressResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> CustomerException.addressNotFound(addressId));
    }
}
