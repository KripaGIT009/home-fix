package com.homefix.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

/**
 * The dispatch pre-filter query ({@link ProviderProfileRepository#findDispatchCandidates}), the
 * Admin provider list projections and the base-location columns against a real database: H2 in PostgreSQL mode, schema generated from the
 * mapping, so the JPQL is parsed and executed rather than assumed.
 */
@DataJpaTest
@ContextConfiguration(classes = ProviderProfileRepositoryJpaTest.JpaConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.datasource.url=jdbc:h2:mem:provider_dispatch;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProviderProfileRepositoryJpaTest {

    private static final double LAT = 25.5560;
    private static final double LON = 84.6603;

    /** A box of roughly ±11 km around Ara. */
    private static final double MIN_LAT = LAT - 0.1;
    private static final double MAX_LAT = LAT + 0.1;
    private static final double MIN_LON = LON - 0.1;
    private static final double MAX_LON = LON + 0.1;

    @Autowired
    private ProviderProfileRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private ProviderProfile persist(Double lat, Double lon, boolean emergency, boolean underReview,
                                    String... tags) {
        ProviderProfile p = ProviderProfile.createWithId(UUID.randomUUID());
        p.setBaseLocation(lat, lon);
        p.setServiceRadiusKm(10);
        p.setEmergencyAvailable(emergency);
        p.replaceSkillTags(List.of(tags));
        if (underReview) {
            p.applyAggregateRating(new BigDecimal("1.0"), new BigDecimal("3.0"));
        }
        return repository.saveAndFlush(p);
    }

    private List<UUID> candidates(boolean emergency, String... tags) {
        return repository.findDispatchCandidates(MIN_LAT, MAX_LAT, MIN_LON, MAX_LON, emergency, List.of(tags))
                .stream().map(ProviderProfile::getId).toList();
    }

    @Test
    void baseLocationRoundTrips() {
        ProviderProfile saved = persist(LAT, LON, true, false, "plumbing");

        ProviderProfile loaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getBaseLatitude()).isEqualTo(LAT);
        assertThat(loaded.getBaseLongitude()).isEqualTo(LON);
    }

    @Test
    void preFilterKeepsOnlyLocatedProvidersInsideTheBox() {
        ProviderProfile inside = persist(LAT + 0.05, LON - 0.05, true, false, "plumbing");
        persist(LAT + 0.5, LON, true, false, "plumbing");     // north of the box
        persist(LAT, LON + 0.5, true, false, "plumbing");     // east of the box
        persist(null, null, true, false, "plumbing");         // no location

        assertThat(candidates(false, "plumbing")).containsExactly(inside.getId());
    }

    @Test
    void preFilterExcludesProvidersUnderReview() {
        ProviderProfile ok = persist(LAT, LON, true, false, "plumbing");
        persist(LAT, LON, true, true, "plumbing");

        assertThat(candidates(false, "plumbing")).containsExactly(ok.getId());
    }

    @Test
    void emergencyFlagIsAppliedOnlyToEmergencySearches() {
        ProviderProfile onCall = persist(LAT, LON, true, false, "plumbing");
        ProviderProfile daytime = persist(LAT, LON, false, false, "plumbing");

        assertThat(candidates(true, "plumbing")).containsExactly(onCall.getId());
        assertThat(candidates(false, "plumbing")).containsExactlyInAnyOrder(onCall.getId(), daytime.getId());
    }

    @Test
    void skillTagsMatchCaseInsensitivelyAndEachProviderAppearsOnce() {
        ProviderProfile both = persist(LAT, LON, true, false, "Plumbing", "ELECTRICAL");
        ProviderProfile one = persist(LAT, LON, true, false, "electrical");
        persist(LAT, LON, true, false, "cleaning");

        // Two matching tags on one provider must not return that provider twice.
        assertThat(candidates(false, "plumbing", "electrical"))
                .containsExactlyInAnyOrder(both.getId(), one.getId());
    }

    // ------------------------------------------------------------------ Admin provider list (Req 19.2)

    private ProviderProfile persistNamed(String name, String... tags) throws InterruptedException {
        Thread.sleep(5); // distinct created_at so newest-first is meaningful
        ProviderProfile p = ProviderProfile.createWithId(UUID.randomUUID());
        p.setDisplayName(name);
        p.replaceSkillTags(List.of(tags));
        return repository.saveAndFlush(p);
    }

    @Test
    void adminRows_areNewestFirstAndBoundedByThePage() throws InterruptedException {
        ProviderProfile first = persistNamed("Asha Electricals", "electrical");
        ProviderProfile second = persistNamed("Ravi Plumbing", "plumbing");
        ProviderProfile third = persistNamed(null, "carpentry");

        List<ProviderAdminRow> rows = repository.findAdminRows(PageRequest.of(0, 200));

        assertThat(rows).extracting(ProviderAdminRow::id).containsExactly(third.getId(), second.getId(), first.getId());
        assertThat(rows.get(1).displayName()).isEqualTo("Ravi Plumbing");
        assertThat(rows.get(1).aggregateRating()).isEqualByComparingTo("0");
        assertThat(repository.findAdminRows(PageRequest.of(0, 2))).hasSize(2);
    }

    @Test
    void adminSearch_isACaseInsensitiveSubstringWithEscapedWildcards() throws InterruptedException {
        ProviderProfile ravi = persistNamed("Ravi Plumbing", "plumbing");
        ProviderProfile percent = persistNamed("100% Fixers", "electrical");
        persistNamed("Asha Electricals", "electrical");
        persistNamed(null, "carpentry");

        assertThat(repository.searchAdminRows("%plumb%", PageRequest.of(0, 200)))
                .extracting(ProviderAdminRow::id).containsExactly(ravi.getId());
        // A literal "%" must not match everything.
        assertThat(repository.searchAdminRows("%100\\%%", PageRequest.of(0, 200)))
                .extracting(ProviderAdminRow::id).containsExactly(percent.getId());
        assertThat(repository.searchAdminRows("%\\%%", PageRequest.of(0, 200)))
                .extracting(ProviderAdminRow::id).containsExactly(percent.getId());
    }

    @Test
    void adminRowsByIdsAndSkillTags_readOnlyTheAskedProviders() throws InterruptedException {
        ProviderProfile ravi = persistNamed("Ravi Plumbing", "plumbing", "drains");
        ProviderProfile asha = persistNamed("Asha Electricals", "electrical");
        persistNamed("Other", "carpentry");

        assertThat(repository.findAdminRowsByIds(List.of(ravi.getId(), asha.getId(), UUID.randomUUID())))
                .extracting(ProviderAdminRow::id).containsExactlyInAnyOrder(ravi.getId(), asha.getId());
        assertThat(repository.findSkillTags(List.of(ravi.getId(), asha.getId())))
                .extracting(ProviderSkillTag::providerId, ProviderSkillTag::tag)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(ravi.getId(), "plumbing"),
                        org.assertj.core.groups.Tuple.tuple(ravi.getId(), "drains"),
                        org.assertj.core.groups.Tuple.tuple(asha.getId(), "electrical"));
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = ProviderProfile.class)
    @EnableJpaRepositories(basePackageClasses = ProviderProfileRepository.class)
    static class JpaConfig {
    }
}
