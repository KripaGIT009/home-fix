package com.homefix.customer.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.customer.booking.BookingClientPort;
import com.homefix.customer.config.CustomerProperties;
import com.homefix.customer.crypto.KmsEncryptionPort;
import com.homefix.customer.domain.Address;
import com.homefix.customer.domain.AddressRepository;
import com.homefix.customer.domain.CustomerProfile;
import com.homefix.customer.domain.CustomerProfileRepository;
import com.homefix.customer.domain.DeletionRequest;
import com.homefix.customer.domain.DeletionRequestRepository;
import com.homefix.customer.geocoding.GeocodingPort;

/**
 * Core customer profile, address, and data-deletion logic (Requirement 2, 26.8, 26.9).
 *
 * <p>All PII (display name, email, resolved street address) is encrypted through the
 * {@link KmsEncryptionPort} before it is ever written to the database, and is never logged
 * (Requirement 2.7, 26.3, 26.4).
 */
@Service
public class CustomerProfileService {

    private static final Logger log = LoggerFactory.getLogger(CustomerProfileService.class);

    private static final String GEOCODE_WARNING =
            "Address could not be resolved automatically; raw coordinates were saved";

    private final CustomerProfileRepository profileRepository;
    private final AddressRepository addressRepository;
    private final DeletionRequestRepository deletionRepository;
    private final KmsEncryptionPort kms;
    private final GeocodingPort geocoding;
    private final BookingClientPort bookingClient;
    private final CustomerProperties properties;
    private final Clock clock;

    public CustomerProfileService(CustomerProfileRepository profileRepository,
                                  AddressRepository addressRepository,
                                  DeletionRequestRepository deletionRepository,
                                  KmsEncryptionPort kms,
                                  GeocodingPort geocoding,
                                  BookingClientPort bookingClient,
                                  CustomerProperties properties,
                                  Clock clock) {
        this.profileRepository = profileRepository;
        this.addressRepository = addressRepository;
        this.deletionRepository = deletionRepository;
        this.kms = kms;
        this.geocoding = geocoding;
        this.bookingClient = bookingClient;
        this.properties = properties;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------
    // Profile (Requirement 2.1, 26.3)
    // ---------------------------------------------------------------------

    /**
     * Updates a customer profile: display name, email, and optional photo URL. The display
     * name and email are encrypted at rest (Requirement 2.7). Field-shape validation
     * (length, email format, photo type/size) is enforced at the API boundary.
     */
    @Transactional
    public CustomerProfile updateProfile(UUID customerId, String displayName, String email,
                                         String photoUrl) {
        CustomerProfile profile = profileRepository.findById(customerId)
                .orElseGet(() -> profileRepository.save(CustomerProfile.forUser(customerId)));

        profile.setDisplayNameEncrypted(kms.encrypt(displayName));
        profile.setEmailEncrypted(kms.encrypt(email));
        if (photoUrl != null) {
            profile.setPhotoUrl(photoUrl);
        }
        CustomerProfile saved = profileRepository.save(profile);
        log.info("Customer profile updated");
        return saved;
    }

    // ---------------------------------------------------------------------
    // Addresses (Requirement 2.2, 2.3, 2.4, 2.6)
    // ---------------------------------------------------------------------

    /**
     * Adds a saved address from GPS coordinates: reverse-geocodes and stores the resolved
     * street address encrypted; on geocoding failure stores raw coordinates with a warning
     * (Requirement 2.2). Enforces the per-customer maximum (Requirement 2.3). The first
     * address for a customer becomes the default.
     */
    @Transactional
    public AddressResult addAddress(UUID customerId, String label, double lat, double lng) {
        long activeCount = addressRepository.countByCustomerIdAndIsActiveTrue(customerId);
        if (activeCount >= properties.getMaxAddresses()) {
            throw CustomerException.addressLimitReached(properties.getMaxAddresses());
        }

        Optional<String> resolved = geocoding.reverseGeocode(lat, lng);
        String encryptedAddress = resolved.map(kms::encrypt).orElse(null);

        boolean makeDefault = activeCount == 0;
        Address address = Address.create(customerId, label, lat, lng, encryptedAddress, makeDefault);
        Address saved = addressRepository.save(address);

        String warning = resolved.isPresent() ? null : GEOCODE_WARNING;
        if (warning != null) {
            log.warn("Reverse-geocoding failed for a new address; stored raw coordinates");
        } else {
            log.info("Address added and reverse-geocoded");
        }
        return new AddressResult(saved.getId(), lat, lng, resolved.isPresent(),
                saved.isDefault(), warning);
    }

    /**
     * Deletes a saved address. Rejected if an active booking references it (Requirement
     * 2.6). If the deleted address was the default and other addresses remain, the most
     * recently added remaining address is promoted to default (Requirement 2.4).
     */
    @Transactional
    public void deleteAddress(UUID customerId, UUID addressId) {
        Address address = addressRepository.findByIdAndCustomerId(addressId, customerId)
                .filter(Address::isActive)
                .orElseThrow(() -> CustomerException.notFound("Address not found"));

        bookingClient.findActiveBookingUsingAddress(customerId, addressId)
                .ifPresent(ref -> {
                    throw CustomerException.addressInUse(ref);
                });

        boolean wasDefault = address.isDefault();
        addressRepository.delete(address);

        if (wasDefault) {
            List<Address> remaining =
                    addressRepository.findByCustomerIdAndIsActiveTrueOrderByCreatedAtDesc(customerId);
            if (!remaining.isEmpty()) {
                Address newDefault = remaining.get(0);
                newDefault.setDefault(true);
                addressRepository.save(newDefault);
                log.info("Promoted most recent address to default after default deletion");
            }
        }
    }

    // ---------------------------------------------------------------------
    // GPS-based location detection (Requirement 2.5)
    // ---------------------------------------------------------------------

    /**
     * Resolves a service location from device-reported GPS coordinates. If GPS was denied
     * or coordinates are unavailable, rejects with an error prompting manual entry
     * (Requirement 2.5). On success, reverse-geocoding is attempted for convenience but a
     * failure to resolve still yields a usable coordinate-based location.
     */
    public DetectedLocation detectLocation(Double lat, Double lng, boolean gpsDenied) {
        if (gpsDenied || lat == null || lng == null) {
            throw CustomerException.gpsUnavailable();
        }
        Optional<String> resolved = geocoding.reverseGeocode(lat, lng);
        return new DetectedLocation(lat, lng, resolved.orElse(null), resolved.isPresent());
    }

    /** A location resolved from device GPS; {@code resolvedAddress} may be null. */
    public record DetectedLocation(double lat, double lng, String resolvedAddress, boolean geocoded) {
    }

    // ---------------------------------------------------------------------
    // Data deletion (Requirement 26.8, 26.9)
    // ---------------------------------------------------------------------

    /**
     * Records and immediately acknowledges a data-deletion request (Requirement 26.8),
     * scheduling anonymization within the configured 30-day window (Requirement 26.9).
     */
    @Transactional
    public DeletionRequest requestDeletion(UUID customerId) {
        if (profileRepository.findById(customerId).isEmpty()) {
            throw CustomerException.notFound("Customer not found");
        }
        Instant now = Instant.now(clock);
        Instant anonymizeAfter = now.plus(properties.getDeletion().getAnonymizeWithin());
        DeletionRequest request = deletionRepository.save(
                DeletionRequest.acknowledge(customerId, now, anonymizeAfter));
        log.info("Data-deletion request acknowledged");
        return request;
    }

    /**
     * Anonymizes PII for all acknowledged deletion requests whose 30-day window has elapsed
     * (Requirement 26.9). Intended to be driven by a scheduled sweep. Returns the number of
     * requests processed.
     */
    @Transactional
    public int anonymizeDueRequests() {
        Instant now = Instant.now(clock);
        List<DeletionRequest> due = deletionRepository
                .findByStatusAndAnonymizeAfterLessThanEqual(DeletionRequest.Status.ACKNOWLEDGED, now);
        for (DeletionRequest request : due) {
            anonymizeCustomer(request.getCustomerId());
            request.markAnonymized(now);
            deletionRepository.save(request);
        }
        if (!due.isEmpty()) {
            log.info("Anonymized {} customer records past their deletion deadline", due.size());
        }
        return due.size();
    }

    private void anonymizeCustomer(UUID customerId) {
        profileRepository.findById(customerId).ifPresent(profile -> {
            String token = "ANONYMIZED-" + UUID.randomUUID();
            // Store non-reversible tokens; these are not KMS ciphertext and cannot be
            // decrypted back to the original PII (Requirement 26.9).
            profile.anonymize(token, token);
            profileRepository.save(profile);
        });
        // Purge PII-bearing addresses for the customer.
        List<Address> addresses = addressRepository.findByCustomerIdAndIsActiveTrue(customerId);
        addressRepository.deleteAll(addresses);
    }
}
