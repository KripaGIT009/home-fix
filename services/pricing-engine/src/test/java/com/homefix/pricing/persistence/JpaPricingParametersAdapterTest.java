package com.homefix.pricing.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static com.homefix.pricing.support.TestData.SUBCATEGORY;
import static com.homefix.pricing.support.TestData.baseParams;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.pricing.domain.PricingParameters;

/**
 * {@link JpaPricingParametersAdapter} against a real database (CODEBASE_REVIEW 8.3: parameters
 * used to live in memory and vanished on every restart).
 *
 * <p>The schema comes from {@code db/pricing-schema.sql}, the reference DDL, and Hibernate runs with
 * {@code ddl-auto=validate} — the production setting — so the test fails if the entity and that DDL
 * disagree on a table, column or type. Runs on H2 in PostgreSQL mode; the ambient test transaction
 * is disabled so every call commits through the adapter's own transaction, as a request would.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaPricingParametersAdapterTest.JpaConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/pricing-schema.sql",
        "spring.datasource.url=jdbc:h2:mem:pricing_parameters;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JpaPricingParametersAdapterTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-02T11:30:00Z");

    @Autowired
    private PricingParametersRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private JpaPricingParametersAdapter adapterAt(Instant now) {
        return new JpaPricingParametersAdapter(repository, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void unknownSubcategory_isEmpty() {
        assertThat(adapterAt(T0).findBySubcategoryId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void savedParameters_surviveANewAdapterInstance() {
        PricingParameters full = new PricingParameters(SUBCATEGORY,
                new BigDecimal("499.00"), new BigDecimal("8.00"), new BigDecimal("150.00"),
                new BigDecimal("100.00"), new BigDecimal("75.00"), new BigDecimal("0.10"),
                new BigDecimal("0.18"), new BigDecimal("2.0"), new BigDecimal("1.25"),
                new BigDecimal("1.00"), new BigDecimal("99999.00"));

        adapterAt(T0).save(full);
        // A fresh adapter stands in for a restarted service: only the database carries state.
        PricingParameters loaded = adapterAt(T1).findBySubcategoryId(SUBCATEGORY).orElseThrow();

        assertThat(loaded.subcategoryId()).isEqualTo(SUBCATEGORY);
        assertThat(loaded.basePrice()).isEqualByComparingTo("499.00");
        assertThat(loaded.perKmRate()).isEqualByComparingTo("8.00");
        assertThat(loaded.maxTravelCharge()).isEqualByComparingTo("150.00");
        assertThat(loaded.nightSurcharge()).isEqualByComparingTo("100.00");
        assertThat(loaded.weekendSurcharge()).isEqualByComparingTo("75.00");
        assertThat(loaded.platformFeeRate()).isEqualByComparingTo("0.10");
        assertThat(loaded.taxRate()).isEqualByComparingTo("0.18");
        assertThat(loaded.emergencyMultiplier()).isEqualByComparingTo("2.0");
        assertThat(loaded.surgeMultiplier()).isEqualByComparingTo("1.25");
        assertThat(loaded.overrideFloor()).isEqualByComparingTo("1.00");
        assertThat(loaded.overrideCeiling()).isEqualByComparingTo("99999.00");
    }

    @Test
    void optionalParameters_roundTripAsNull() {
        PricingParameters minimal = new PricingParameters(SUBCATEGORY, new BigDecimal("100.00"),
                null, null, null, null, null, null, null, null, null, null);

        adapterAt(T0).save(minimal);
        PricingParameters loaded = adapterAt(T0).findBySubcategoryId(SUBCATEGORY).orElseThrow();

        assertThat(loaded.basePrice()).isEqualByComparingTo("100.00");
        assertThat(loaded.perKmRate()).isNull();
        assertThat(loaded.platformFeeRate()).isNull();
        assertThat(loaded.surgeMultiplier()).isNull();
        assertThat(loaded.overrideCeiling()).isNull();
    }

    @Test
    void resaving_replacesTheRowInPlaceAndKeepsItsCreationTime() {
        adapterAt(T0).save(baseParams("100.00"));

        PricingParameters returned = adapterAt(T1).save(new PricingParameters(SUBCATEGORY,
                new BigDecimal("175.00"), null, null, null, null, null, null, null, null, null, null));

        assertThat(returned.basePrice()).isEqualByComparingTo("175.00");
        assertThat(repository.count()).isEqualTo(1);
        PricingParameters loaded = adapterAt(T1).findBySubcategoryId(SUBCATEGORY).orElseThrow();
        assertThat(loaded.basePrice()).isEqualByComparingTo("175.00");
        // A PUT replaces the whole set: a field omitted from the update is cleared, not kept.
        assertThat(loaded.maxTravelCharge()).isNull();
        PricingParametersEntity row = repository.findById(SUBCATEGORY).orElseThrow();
        assertThat(row.getCreatedAt()).isEqualTo(T0);
        assertThat(row.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void rowsLiveInThePricingSchema() {
        adapterAt(T0).save(baseParams("100.00"));

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pricing.pricing_parameters WHERE subcategory_id = ?",
                Integer.class, SUBCATEGORY);

        assertThat(rows).isEqualTo(1);
    }

    @Test
    void findAll_returnsNewestUpdatedFirstAndHonoursTheLimit() {
        UUID older = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID newer = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
        adapterAt(T0).save(new PricingParameters(older, new BigDecimal("100.00"),
                null, null, null, null, null, null, null, null, null, null));
        adapterAt(T1).save(new PricingParameters(newer, new BigDecimal("200.00"),
                null, null, null, null, null, null, null, null, null, null));

        assertThat(adapterAt(T1).findAll(10))
                .extracting(PricingParameters::subcategoryId)
                .containsExactly(newer, older);
        assertThat(adapterAt(T1).findAll(1))
                .extracting(PricingParameters::subcategoryId)
                .containsExactly(newer);
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = PricingParametersEntity.class)
    @EnableJpaRepositories(basePackageClasses = PricingParametersRepository.class)
    static class JpaConfig {
    }
}
