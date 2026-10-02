package com.homefix.provider.eligibility;

import static com.homefix.provider.eligibility.ProfileFixture.ARA_LAT;
import static com.homefix.provider.eligibility.ProfileFixture.ARA_LON;
import static com.homefix.provider.eligibility.ProfileFixture.DEG_LAT_PER_KM;
import static com.homefix.provider.eligibility.ProfileFixture.provider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.homefix.provider.config.ProviderProperties;
import com.homefix.provider.domain.ProviderProfile;
import com.homefix.provider.service.ProviderException;
import com.homefix.provider.support.InMemoryProviderProfileRepository;
import com.homefix.provider.verification.VerificationClientPort;

/**
 * The full eligibility search behind {@code GET /internal/providers/eligible}: SQL pre-filter (via
 * the in-memory repository, which mirrors it), per-provider rules, and the Verification Service
 * gate, with a fixed clock.
 */
class ProviderEligibilityServiceTest {

    /** Friday 2 October 2026, 04:30 UTC = 10:00 IST. */
    private static final Instant NOW = Instant.parse("2026-10-02T04:30:00Z");

    private InMemoryProviderProfileRepository repository;
    private FakeVerification verification;
    private ProviderProperties props;
    private ProviderEligibilityService service;

    @BeforeEach
    void setUp() {
        repository = spy(new InMemoryProviderProfileRepository());
        verification = new FakeVerification();
        props = new ProviderProperties();
        service = new ProviderEligibilityService(repository, verification, props,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ProviderProfile save(ProfileFixture fixture) {
        return repository.save(fixture.build());
    }

    private static EligibilityQuery query(double radiusKm, boolean emergency, String... tags) {
        return new EligibilityQuery(UUID.randomUUID(), ARA_LAT, ARA_LON, radiusKm, emergency, List.of(tags));
    }

    @Test
    void returnsOnlyApprovedProviders() {
        ProviderProfile approved = save(provider().kmNorthOfAra(2));
        ProviderProfile pending = save(provider().kmNorthOfAra(3));
        verification.approve(approved.getId());

        List<ProviderMatch> result = service.findEligible(query(10, false, "plumbing"));

        assertThat(result).extracting(ProviderMatch::providerId).containsExactly(approved.getId());
        assertThat(verification.asked).containsExactlyInAnyOrder(approved.getId(), pending.getId());
    }

    @Test
    void verificationIsAskedOnlyAboutProvidersWhoOtherwiseQualify() {
        ProviderProfile near = save(provider().kmNorthOfAra(2));
        save(provider().kmNorthOfAra(30));                  // outside the radius
        save(provider().kmNorthOfAra(1).tags("cleaning"));  // no shared tag
        save(provider().kmNorthOfAra(1).underReview());     // flagged
        save(provider().noLocation());                      // nothing to measure from
        verification.approveEveryone = true;

        List<ProviderMatch> result = service.findEligible(query(10, false, "plumbing"));

        assertThat(result).extracting(ProviderMatch::providerId).containsExactly(near.getId());
        assertThat(verification.asked).containsExactly(near.getId());
    }

    @Test
    void verificationUnavailable_failsClosed() {
        save(provider().kmNorthOfAra(2));
        // The adapter reports nobody approved when the Verification Service cannot answer.
        verification.approveEveryone = false;

        assertThat(service.findEligible(query(10, false, "plumbing"))).isEmpty();
    }

    @Test
    void noLocalMatches_neverCallsTheVerificationService() {
        save(provider().kmNorthOfAra(50));
        verification.approveEveryone = true;

        assertThat(service.findEligible(query(10, false, "plumbing"))).isEmpty();
        assertThat(verification.calls).isZero();
    }

    @Test
    void emergencySearch_excludesProvidersNotEmergencyAvailable() {
        ProviderProfile onCall = save(provider().kmNorthOfAra(2));
        save(provider().kmNorthOfAra(1).emergencyAvailable(false));
        verification.approveEveryone = true;

        assertThat(service.findEligible(query(10, true, "plumbing")))
                .extracting(ProviderMatch::providerId).containsExactly(onCall.getId());
    }

    @Test
    void resultsAreOrderedClosestFirst() {
        ProviderProfile far = save(provider().kmNorthOfAra(6));
        ProviderProfile near = save(provider().kmNorthOfAra(1));
        ProviderProfile middle = save(provider().kmNorthOfAra(3));
        verification.approveEveryone = true;

        assertThat(service.findEligible(query(10, false, "plumbing")))
                .extracting(ProviderMatch::providerId)
                .containsExactly(near.getId(), middle.getId(), far.getId());
    }

    @Test
    void availabilityIsReadInTheConfiguredZone() {
        // 04:30 UTC is 10:00 in Kolkata: inside a 9-12 IST slot, outside it if read as UTC.
        DayOfWeek friday = DayOfWeek.FRIDAY;
        ProviderProfile p = save(provider().slot(friday, 9, 12));
        verification.approveEveryone = true;

        assertThat(service.findEligible(query(10, false, "plumbing")))
                .extracting(ProviderMatch::providerId).containsExactly(p.getId());

        props.setAvailabilityZone("UTC");
        assertThat(service.findEligible(query(10, false, "plumbing"))).isEmpty();
    }

    @Test
    void noRequiredTags_returnsNobodyWithoutQuerying() {
        save(provider());
        verification.approveEveryone = true;

        assertThat(service.findEligible(query(10, false))).isEmpty();
        verify(repository, never()).findDispatchCandidates(
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyBoolean(), anyCollection());
    }

    @Test
    void sqlBoxIsSizedByTheSmallerOfSearchRadiusAndMaximumServiceRadius() {
        props.setMaxServiceRadiusKm(100);
        ArgumentCaptor<Double> minLat = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> maxLat = ArgumentCaptor.forClass(Double.class);

        service.findEligible(query(500, false, "Plumbing"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> tags = ArgumentCaptor.forClass(Collection.class);
        verify(repository).findDispatchCandidates(minLat.capture(), maxLat.capture(),
                anyDouble(), anyDouble(), anyBoolean(), tags.capture());
        assertThat(maxLat.getValue() - ARA_LAT).isCloseTo(100 * DEG_LAT_PER_KM, within(1e-9));
        assertThat(ARA_LAT - minLat.getValue()).isCloseTo(100 * DEG_LAT_PER_KM, within(1e-9));
        // Tags reach SQL normalised, matching the lower(tag) comparison there.
        assertThat(tags.getValue()).containsExactly("plumbing");
    }

    @Test
    void outOfRangeQuery_isRejected() {
        assertThatThrownBy(() -> service.findEligible(new EligibilityQuery(null, 91, ARA_LON, 10, false,
                List.of("plumbing"))))
                .isInstanceOf(ProviderException.class).hasMessageContaining("lat");
        assertThatThrownBy(() -> service.findEligible(new EligibilityQuery(null, ARA_LAT, -181, 10, false,
                List.of("plumbing"))))
                .isInstanceOf(ProviderException.class).hasMessageContaining("lon");
        assertThatThrownBy(() -> service.findEligible(new EligibilityQuery(null, ARA_LAT, ARA_LON, 0, false,
                List.of("plumbing"))))
                .isInstanceOf(ProviderException.class).hasMessageContaining("radiusKm");
        assertThatThrownBy(() -> service.findEligible(new EligibilityQuery(null, ARA_LAT, ARA_LON,
                Double.NaN, false, List.of("plumbing"))))
                .isInstanceOf(ProviderException.class).hasMessageContaining("radiusKm");
    }

    /** Records what it was asked and approves a configured set. */
    private static final class FakeVerification implements VerificationClientPort {

        private final Set<UUID> approved = new HashSet<>();
        private final List<UUID> asked = new ArrayList<>();
        private boolean approveEveryone;
        private int calls;

        void approve(UUID id) {
            approved.add(id);
        }

        @Override
        public Set<UUID> approvedAmong(Collection<UUID> providerIds) {
            calls++;
            asked.addAll(providerIds);
            Set<UUID> result = new HashSet<>();
            for (UUID id : providerIds) {
                if (approveEveryone || approved.contains(id)) {
                    result.add(id);
                }
            }
            return result;
        }
    }
}
