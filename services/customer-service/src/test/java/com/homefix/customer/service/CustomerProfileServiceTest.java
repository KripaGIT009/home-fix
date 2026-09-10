package com.homefix.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.homefix.customer.config.CustomerProperties;
import com.homefix.customer.domain.Address;
import com.homefix.customer.domain.CustomerProfile;
import com.homefix.customer.domain.CustomerProfileRepository;
import com.homefix.customer.domain.DeletionRequest;
import com.homefix.customer.domain.DeletionRequestRepository;
import com.homefix.customer.service.CustomerProfileService.DetectedLocation;
import com.homefix.customer.support.FakeBookingClientPort;
import com.homefix.customer.support.FakeGeocodingPort;
import com.homefix.customer.support.FakeKmsEncryptionPort;
import com.homefix.customer.support.InMemoryAddressRepository;

/**
 * Unit tests for the Customer Service profile, address, GPS, and deletion logic
 * (Requirement 2, 26.8, 26.9). Uses in-memory / fake ports — no DB, Redis, or Spring
 * context required.
 */
@ExtendWith(MockitoExtension.class)
class CustomerProfileServiceTest {

    private static final UUID CUSTOMER = UUID.randomUUID();

    @Mock
    private CustomerProfileRepository profileRepository;

    @Mock
    private DeletionRequestRepository deletionRepository;

    private InMemoryAddressRepository addressRepository;
    private FakeKmsEncryptionPort kms;
    private FakeGeocodingPort geocoding;
    private FakeBookingClientPort bookingClient;
    private CustomerProperties properties;
    private Clock clock;

    private CustomerProfileService service;

    @BeforeEach
    void setUp() {
        addressRepository = new InMemoryAddressRepository();
        kms = new FakeKmsEncryptionPort();
        geocoding = new FakeGeocodingPort();
        bookingClient = new FakeBookingClientPort();
        properties = new CustomerProperties();
        clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        service = new CustomerProfileService(profileRepository, addressRepository,
                deletionRepository, kms, geocoding, bookingClient, properties, clock);
    }

    private void stubProfilePersistence() {
        lenient().when(profileRepository.findById(CUSTOMER)).thenReturn(Optional.empty());
        lenient().when(profileRepository.save(any(CustomerProfile.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ----- Profile update + PII encryption (Requirement 2.1, 2.7, 26.3) -----

    @Test
    void updateProfile_encryptsPiiFields() {
        stubProfilePersistence();

        CustomerProfile profile = service.updateProfile(
                CUSTOMER, "Sherlock Holmes", "sherlock@example.com", "https://cdn/photo.png");

        // Stored values must be ciphertext, never the plaintext PII.
        assertThat(profile.getDisplayNameEncrypted()).isNotNull();
        assertThat(profile.getEmailEncrypted()).isNotNull();
        assertThat(profile.getDisplayNameEncrypted()).doesNotContain("Sherlock Holmes");
        assertThat(profile.getEmailEncrypted()).doesNotContain("sherlock@example.com");
        // And they must round-trip back to the original values under the key.
        assertThat(kms.decrypt(profile.getDisplayNameEncrypted())).isEqualTo("Sherlock Holmes");
        assertThat(kms.decrypt(profile.getEmailEncrypted())).isEqualTo("sherlock@example.com");
        assertThat(profile.getPhotoUrl()).isEqualTo("https://cdn/photo.png");
    }

    // ----- Address creation + geocoding (Requirement 2.2) -----

    @Test
    void addAddress_geocodesAndEncryptsResolvedAddress() {
        geocoding.resolveTo("221B Baker Street, London");

        AddressResult result = service.addAddress(CUSTOMER, "Home", 51.5237, -0.1585);

        assertThat(result.geocoded()).isTrue();
        assertThat(result.warning()).isNull();
        assertThat(result.isDefault()).isTrue(); // first address becomes default

        Address stored = addressRepository.findById(result.addressId()).orElseThrow();
        assertThat(stored.getAddressTextEncrypted()).isNotNull();
        assertThat(stored.getAddressTextEncrypted()).doesNotContain("Baker Street");
        assertThat(kms.decrypt(stored.getAddressTextEncrypted()))
                .isEqualTo("221B Baker Street, London");
    }

    @Test
    void addAddress_whenGeocodingFails_storesRawCoordinatesWithWarning() {
        geocoding.failNext();

        AddressResult result = service.addAddress(CUSTOMER, "Home", 51.5237, -0.1585);

        assertThat(result.geocoded()).isFalse();
        assertThat(result.warning()).isNotBlank();
        assertThat(result.lat()).isEqualTo(51.5237);
        assertThat(result.lng()).isEqualTo(-0.1585);

        Address stored = addressRepository.findById(result.addressId()).orElseThrow();
        assertThat(stored.getAddressTextEncrypted()).isNull();
        assertThat(stored.getLat()).isEqualTo(51.5237);
        assertThat(stored.getLng()).isEqualTo(-0.1585);
    }

    // ----- Address limit (Requirement 2.3) -----

    @Test
    void addAddress_beyondLimit_isRejected() {
        for (int i = 0; i < properties.getMaxAddresses(); i++) {
            service.addAddress(CUSTOMER, "Addr" + i, 10.0 + i, 20.0 + i);
        }
        assertThat(addressRepository.countByCustomerIdAndIsActiveTrue(CUSTOMER))
                .isEqualTo(properties.getMaxAddresses());

        assertThatThrownBy(() -> service.addAddress(CUSTOMER, "OneTooMany", 5.0, 5.0))
                .isInstanceOf(CustomerException.class)
                .satisfies(ex -> {
                    CustomerException ce = (CustomerException) ex;
                    assertThat(ce.getErrorCode()).isEqualTo("ADDRESS_LIMIT_REACHED");
                    assertThat(ce.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
    }

    // ----- Default promotion on delete (Requirement 2.4) -----

    @Test
    void deletingDefaultAddress_promotesMostRecentRemaining() {
        AddressResult first = service.addAddress(CUSTOMER, "First", 1.0, 1.0);  // default
        service.addAddress(CUSTOMER, "Second", 2.0, 2.0);
        AddressResult third = service.addAddress(CUSTOMER, "Third", 3.0, 3.0);

        // Sanity: first is the default.
        assertThat(addressRepository.findById(first.addressId()).orElseThrow().isDefault()).isTrue();

        service.deleteAddress(CUSTOMER, first.addressId());

        // Most recently added remaining address (Third) becomes the new default.
        List<Address> remaining =
                addressRepository.findByCustomerIdAndIsActiveTrueOrderByCreatedAtDesc(CUSTOMER);
        assertThat(remaining).hasSize(2);
        Address promoted = addressRepository.findById(third.addressId()).orElseThrow();
        assertThat(promoted.isDefault()).isTrue();
    }

    @Test
    void deletingNonDefaultAddress_doesNotChangeDefault() {
        AddressResult first = service.addAddress(CUSTOMER, "First", 1.0, 1.0);  // default
        AddressResult second = service.addAddress(CUSTOMER, "Second", 2.0, 2.0);

        service.deleteAddress(CUSTOMER, second.addressId());

        assertThat(addressRepository.findById(first.addressId()).orElseThrow().isDefault()).isTrue();
    }

    // ----- Active-booking block (Requirement 2.6) -----

    @Test
    void deletingAddressUsedByActiveBooking_isRejected() {
        AddressResult addr = service.addAddress(CUSTOMER, "Home", 1.0, 1.0);
        bookingClient.markInUse("BK-2024-000123");

        assertThatThrownBy(() -> service.deleteAddress(CUSTOMER, addr.addressId()))
                .isInstanceOf(CustomerException.class)
                .satisfies(ex -> {
                    CustomerException ce = (CustomerException) ex;
                    assertThat(ce.getErrorCode()).isEqualTo("ADDRESS_IN_USE");
                    assertThat(ce.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ce.getMessage()).contains("BK-2024-000123");
                });

        // Address is untouched.
        assertThat(addressRepository.findById(addr.addressId())).isPresent();
    }

    // ----- GPS location detection (Requirement 2.5) -----

    @Test
    void detectLocation_withCoordinates_returnsLocation() {
        geocoding.resolveTo("Somewhere");
        DetectedLocation location = service.detectLocation(12.34, 56.78, false);
        assertThat(location.lat()).isEqualTo(12.34);
        assertThat(location.lng()).isEqualTo(56.78);
        assertThat(location.geocoded()).isTrue();
        assertThat(location.resolvedAddress()).isEqualTo("Somewhere");
    }

    @Test
    void detectLocation_whenGpsDenied_isRejected() {
        assertThatThrownBy(() -> service.detectLocation(null, null, true))
                .isInstanceOf(CustomerException.class)
                .satisfies(ex -> {
                    CustomerException ce = (CustomerException) ex;
                    assertThat(ce.getErrorCode()).isEqualTo("GPS_UNAVAILABLE");
                    assertThat(ce.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void detectLocation_whenCoordinatesMissing_isRejected() {
        assertThatThrownBy(() -> service.detectLocation(null, 5.0, false))
                .isInstanceOf(CustomerException.class)
                .satisfies(ex -> assertThat(((CustomerException) ex).getErrorCode())
                        .isEqualTo("GPS_UNAVAILABLE"));
    }

    // ----- Deletion request + anonymization (Requirement 26.8, 26.9) -----

    @Test
    void requestDeletion_acknowledgesAndSchedulesAnonymizationWithin30Days() {
        CustomerProfile profile = CustomerProfile.forUser(CUSTOMER);
        when(profileRepository.findById(CUSTOMER)).thenReturn(Optional.of(profile));
        when(deletionRepository.save(any(DeletionRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DeletionRequest request = service.requestDeletion(CUSTOMER);

        assertThat(request.getStatus()).isEqualTo(DeletionRequest.Status.ACKNOWLEDGED);
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        assertThat(request.getAcknowledgedAt()).isEqualTo(now);
        assertThat(request.getAnonymizeAfter()).isEqualTo(now.plus(Duration.ofDays(30)));
    }

    @Test
    void requestDeletion_forUnknownCustomer_isRejected() {
        when(profileRepository.findById(CUSTOMER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requestDeletion(CUSTOMER))
                .isInstanceOf(CustomerException.class)
                .satisfies(ex -> assertThat(((CustomerException) ex).getErrorCode())
                        .isEqualTo("CUSTOMER_NOT_FOUND"));
    }

    @Test
    void anonymizeDueRequests_replacesPiiWithNonReversibleTokens() {
        CustomerProfile profile = CustomerProfile.forUser(CUSTOMER);
        profile.setDisplayNameEncrypted(kms.encrypt("Sherlock Holmes"));
        profile.setEmailEncrypted(kms.encrypt("sherlock@example.com"));
        service.addAddress(CUSTOMER, "Home", 1.0, 1.0);

        DeletionRequest due = DeletionRequest.acknowledge(CUSTOMER,
                Instant.parse("2023-12-01T00:00:00Z"), Instant.parse("2023-12-31T00:00:00Z"));
        when(deletionRepository.findByStatusAndAnonymizeAfterLessThanEqual(
                any(), any())).thenReturn(List.of(due));
        when(profileRepository.findById(CUSTOMER)).thenReturn(Optional.of(profile));
        lenient().when(profileRepository.save(any(CustomerProfile.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(deletionRepository.save(any(DeletionRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        int processed = service.anonymizeDueRequests();

        assertThat(processed).isEqualTo(1);
        assertThat(profile.isAnonymized()).isTrue();
        // PII is replaced with a non-reversible token, not decryptable back to the original.
        assertThat(profile.getDisplayNameEncrypted()).startsWith("ANONYMIZED-");
        assertThat(profile.getEmailEncrypted()).startsWith("ANONYMIZED-");
        assertThat(due.getStatus()).isEqualTo(DeletionRequest.Status.ANONYMIZED);
        // PII-bearing addresses are purged.
        assertThat(addressRepository.findByCustomerIdAndIsActiveTrue(CUSTOMER)).isEmpty();
    }
}
