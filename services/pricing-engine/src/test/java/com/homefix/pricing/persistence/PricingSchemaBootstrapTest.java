package com.homefix.pricing.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static com.homefix.pricing.support.TestData.SUBCATEGORY;
import static com.homefix.pricing.support.TestData.baseParams;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The local stack's startup path on a database that has never seen the {@code pricing} schema.
 *
 * <p>{@code docker/init-db.sql} creates the schema, but Postgres runs it only when the data volume
 * is first initialised, so a volume that predates this service's persistence has no such schema.
 * The compose file runs services with {@code ddl-auto=update}, which creates tables but — on its
 * own — not schemas. {@code application.yml} therefore sets
 * {@code hibernate.hbm2ddl.create_namespaces}; this test boots with that file's JPA settings
 * (default schema and that flag, no overrides) against an empty database and checks the adapter
 * works.
 */
@DataJpaTest
@ContextConfiguration(classes = PricingSchemaBootstrapTest.JpaConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.datasource.url=jdbc:h2:mem:pricing_bootstrap;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PricingSchemaBootstrapTest {

    @Autowired
    private PricingParametersRepository repository;

    @Test
    void updateModeCreatesTheMissingSchemaAndTable() {
        JpaPricingParametersAdapter adapter = new JpaPricingParametersAdapter(repository);

        adapter.save(baseParams("100.00"));

        assertThat(adapter.findBySubcategoryId(SUBCATEGORY)).hasValueSatisfying(
                p -> assertThat(p.basePrice()).isEqualByComparingTo("100.00"));
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = PricingParametersEntity.class)
    @EnableJpaRepositories(basePackageClasses = PricingParametersRepository.class)
    static class JpaConfig {
    }
}
