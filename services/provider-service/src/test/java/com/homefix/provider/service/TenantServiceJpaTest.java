package com.homefix.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.provider.auth.AuthUserClientPort;
import com.homefix.provider.catalog.CatalogClientPort;
import com.homefix.provider.config.ProviderProperties;
import com.homefix.provider.domain.AvailabilitySlot;
import com.homefix.provider.domain.ProviderProfile;
import com.homefix.provider.domain.ProviderProfileRepository;
import com.homefix.provider.domain.Tenant;
import com.homefix.provider.domain.TenantAdmin;
import com.homefix.provider.domain.TenantAdminRepository;
import com.homefix.provider.domain.TenantRepository;
import com.homefix.provider.domain.TenantStatus;
import com.homefix.provider.eligibility.GeoMath;
import com.homefix.provider.service.TenantViews.AdminView;
import com.homefix.provider.service.TenantViews.CoveringTenant;
import com.homefix.provider.service.TenantViews.MembershipView;
import com.homefix.provider.service.TenantViews.TeamProviderView;
import com.homefix.provider.service.TenantViews.TenantView;
import com.homefix.provider.support.FakeAuthUserClient;
import com.homefix.provider.support.FakeVerificationStatuses;

/**
 * {@link TenantService} and {@link TenantTeamService} against a real database (H2 in PostgreSQL
 * mode, schema from the mapping) with scripted Auth, Catalog and Verification ports, so the JPQL,
 * the conditional membership statements and the primary-key guarantees run for real
 * (Requirements MT-1 to MT-3, MT-10; Properties MT2, MT5, MT8).
 *
 * <p>Test-managed transactions are switched off: each repository call commits on its own, exactly
 * as in production, so a lost race or a version check fails where it would in the service.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ContextConfiguration(classes = TenantServiceJpaTest.JpaConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.datasource.url=jdbc:h2:mem:provider_tenants;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TenantServiceJpaTest {

    private static final double LAT = 25.5560;
    private static final double LON = 84.6603;

    /** Monday 2026-10-05 12:00 in Asia/Kolkata. */
    private static final Clock MONDAY_NOON_IST =
            Clock.fixed(Instant.parse("2026-10-05T06:30:00Z"), ZoneOffset.UTC);

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private TenantAdminRepository admins;
    @Autowired
    private ProviderProfileRepository profiles;

    private final UUID platformAdmin = UUID.randomUUID();
    private final UUID plumbing = UUID.randomUUID();
    private final UUID electrical = UUID.randomUUID();
    private final Set<UUID> inactiveCategories = new HashSet<>();

    private FakeAuthUserClient auth;
    private FakeVerificationStatuses verification;
    private TenantService service;
    private TenantTeamService team;

    @BeforeEach
    void setUp() {
        profiles.deleteAll();
        admins.deleteAll();
        tenants.deleteAll();
        inactiveCategories.clear();
        auth = new FakeAuthUserClient();
        verification = new FakeVerificationStatuses();
        CatalogClientPort catalog = new CatalogClientPort() {
            @Override
            public boolean isCategoryActive(UUID categoryId) {
                return !inactiveCategories.contains(categoryId);
            }

            @Override
            public boolean isSubcategoryActive(UUID categoryId, UUID subcategoryId) {
                return true;
            }
        };
        service = new TenantService(tenants, admins, profiles, catalog, auth);
        team = new TenantTeamService(profiles, tenants, auth, verification, new ProviderProperties(),
                MONDAY_NOON_IST);
    }

    private TenantCommand command(String name, Double lat, Double lon, String radius, List<UUID> categories) {
        return new TenantCommand(name, "+919000000031", "ops@agency.example", lat, lon,
                radius == null ? null : new BigDecimal(radius), categories, null);
    }

    private TenantCommand valid() {
        return command("Ara Home Services", LAT, LON, "20", List.of(plumbing));
    }

    private Tenant createTenant(String name, double lat, double lon, String radius, UUID... categories) {
        return service.create(command(name, lat, lon, radius, List.of(categories)), platformAdmin).tenant();
    }

    private ProviderProfile persistProvider(String mobile, String name, String... tags) {
        UUID userId = auth.register(mobile, AuthUserClientPort.SERVICE_PROVIDER);
        ProviderProfile p = ProviderProfile.createWithId(userId);
        p.setDisplayName(name);
        p.replaceSkillTags(List.of(tags));
        return profiles.saveAndFlush(p);
    }

    private void assertRefused(Runnable action, HttpStatus status, String errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getErrorCode()).isEqualTo(errorCode);
                });
    }

    // ============================= Registry validation (Requirement MT-1.2) =============

    @Test
    void create_acceptsEveryBoundaryValueAndStoresTheTenantActive() {
        for (String radius : List.of("1", "100", "1.04", "99.96")) {
            Tenant t = createTenant("Ab", 90.0, 180.0, radius, plumbing);
            assertThat(t.getStatus()).isEqualTo(TenantStatus.ACTIVE);
            assertThat(t.getUpdatedBy()).isEqualTo(platformAdmin);
        }
        Tenant edge = createTenant("x".repeat(120), -90.0, -180.0, "50", plumbing, electrical);

        Tenant loaded = tenants.findById(edge.getId()).orElseThrow();
        assertThat(loaded.getServiceRadiusKm()).isEqualByComparingTo("50.0");
        assertThat(loaded.getCategoryIds()).containsExactlyInAnyOrder(plumbing, electrical);
        assertThat(loaded.getContactPhone()).isEqualTo("+919000000031");
    }

    @Test
    void create_namesTheRuleThatFailed() {
        assertRefused(() -> service.create(command("A", LAT, LON, "20", List.of(plumbing)), platformAdmin),
                HttpStatus.BAD_REQUEST, "INVALID_TENANT_NAME");
        assertRefused(() -> service.create(command("x".repeat(121), LAT, LON, "20", List.of(plumbing)),
                platformAdmin), HttpStatus.BAD_REQUEST, "INVALID_TENANT_NAME");
        assertRefused(() -> service.create(command("   ", LAT, LON, "20", List.of(plumbing)), platformAdmin),
                HttpStatus.BAD_REQUEST, "INVALID_TENANT_NAME");
        assertRefused(() -> service.create(command("Agency", 90.0001, LON, "20", List.of(plumbing)),
                platformAdmin), HttpStatus.BAD_REQUEST, "INVALID_LATITUDE");
        assertRefused(() -> service.create(command("Agency", null, LON, "20", List.of(plumbing)),
                platformAdmin), HttpStatus.BAD_REQUEST, "INVALID_LATITUDE");
        assertRefused(() -> service.create(command("Agency", LAT, -180.0001, "20", List.of(plumbing)),
                platformAdmin), HttpStatus.BAD_REQUEST, "INVALID_LONGITUDE");
        assertRefused(() -> service.create(command("Agency", LAT, LON, "0.99", List.of(plumbing)),
                platformAdmin), HttpStatus.BAD_REQUEST, "INVALID_SERVICE_RADIUS");
        assertRefused(() -> service.create(command("Agency", LAT, LON, "100.01", List.of(plumbing)),
                platformAdmin), HttpStatus.BAD_REQUEST, "INVALID_SERVICE_RADIUS");
        assertRefused(() -> service.create(command("Agency", LAT, LON, null, List.of(plumbing)),
                platformAdmin), HttpStatus.BAD_REQUEST, "INVALID_SERVICE_RADIUS");
        assertRefused(() -> service.create(command("Agency", LAT, LON, "20", List.of()), platformAdmin),
                HttpStatus.BAD_REQUEST, "CATEGORIES_REQUIRED");
        assertRefused(() -> service.create(command("Agency", LAT, LON, "20", null), platformAdmin),
                HttpStatus.BAD_REQUEST, "CATEGORIES_REQUIRED");
        assertRefused(() -> service.create(new TenantCommand("Agency", "call me", null, LAT, LON,
                BigDecimal.TEN, List.of(plumbing), null), platformAdmin), HttpStatus.BAD_REQUEST,
                "INVALID_CONTACT_PHONE");
        assertRefused(() -> service.create(new TenantCommand("Agency", null, "not-an-email", LAT, LON,
                BigDecimal.TEN, List.of(plumbing), null), platformAdmin), HttpStatus.BAD_REQUEST,
                "INVALID_CONTACT_EMAIL");

        inactiveCategories.add(electrical);
        assertRefused(() -> service.create(command("Agency", LAT, LON, "20", List.of(plumbing, electrical)),
                platformAdmin), HttpStatus.BAD_REQUEST, "INACTIVE_CATEGORY");

        assertThat(tenants.count()).isZero();
    }

    // ============================= Update (Requirement MT-1.3) ==========================

    @Test
    void update_replacesEverythingAtOnceAndRecordsTheActor() {
        Tenant t = createTenant("Ara Home Services", LAT, LON, "20", plumbing);
        UUID otherAdmin = UUID.randomUUID();

        TenantView updated = service.update(t.getId(), new TenantCommand("Ara Services", null, null,
                25.6, 84.7, new BigDecimal("35.5"), List.of(electrical), "SUSPENDED"), otherAdmin);

        Tenant loaded = tenants.findById(t.getId()).orElseThrow();
        assertThat(updated.tenant().getName()).isEqualTo("Ara Services");
        assertThat(loaded.getStatus()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(loaded.getCategoryIds()).containsExactly(electrical);
        assertThat(loaded.getServiceRadiusKm()).isEqualByComparingTo("35.5");
        assertThat(loaded.getContactPhone()).isNull();
        assertThat(loaded.getUpdatedBy()).isEqualTo(otherAdmin);

        // Status omitted: kept.
        service.update(t.getId(), valid(), platformAdmin);
        assertThat(tenants.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TenantStatus.SUSPENDED);
    }

    @Test
    void update_refusesUnknownTenantsAndStatuses() {
        Tenant t = createTenant("Ara Home Services", LAT, LON, "20", plumbing);

        assertRefused(() -> service.update(UUID.randomUUID(), valid(), platformAdmin),
                HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND");
        assertRefused(() -> service.update(t.getId(), new TenantCommand("Ara", null, null, LAT, LON,
                BigDecimal.TEN, List.of(plumbing), "CLOSED"), platformAdmin),
                HttpStatus.BAD_REQUEST, "INVALID_TENANT_STATUS");
        assertRefused(() -> service.update(t.getId(), command("Ara", LAT, LON, "101", List.of(plumbing)),
                platformAdmin), HttpStatus.BAD_REQUEST, "INVALID_SERVICE_RADIUS");
        assertThat(tenants.findById(t.getId()).orElseThrow().getServiceRadiusKm()).isEqualByComparingTo("20");
    }

    @Test
    void aStaleCopyCannotOverwriteANewerEdit() {
        Tenant t = createTenant("Ara Home Services", LAT, LON, "20", plumbing);
        Tenant stale = tenants.findById(t.getId()).orElseThrow();

        service.update(t.getId(), command("Renamed", LAT, LON, "20", List.of(plumbing)), platformAdmin);
        stale.update(new Tenant.TenantDetails("Stale", null, null, LAT, LON, BigDecimal.TEN, Set.of(plumbing)),
                TenantStatus.ACTIVE, platformAdmin);

        assertThatThrownBy(() -> tenants.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(tenants.findById(t.getId()).orElseThrow().getName()).isEqualTo("Renamed");
    }

    @Test
    void list_carriesProviderAndAdminCounts() {
        Tenant a = createTenant("Alpha Agency", LAT, LON, "20", plumbing);
        Tenant b = createTenant("Beta Agency", LAT, LON, "20", plumbing);
        ProviderProfile p1 = persistProvider("+919000000011", "Ravi", "plumbing");
        ProviderProfile p2 = persistProvider("+919000000012", "Asha", "electrical");
        profiles.attachToTenant(p1.getId(), a.getId());
        profiles.attachToTenant(p2.getId(), a.getId());
        auth.register(UUID.randomUUID(), "+919000000031");
        service.addAdmin(a.getId(), "+919000000031", platformAdmin);

        List<TenantView> list = service.list();

        assertThat(list).extracting(v -> v.tenant().getName()).containsExactly("Alpha Agency", "Beta Agency");
        assertThat(list.get(0).providerCount()).isEqualTo(2);
        assertThat(list.get(0).adminCount()).isEqualTo(1);
        assertThat(list.get(1).providerCount()).isZero();
        assertThat(service.get(b.getId()).adminCount()).isZero();
    }

    // ============================= Coverage (Requirement MT-4.1, Property MT2) ==========

    @Test
    void covering_isInclusiveAtTheRadiusAndNearestFirst() {
        Tenant near = createTenant("Near Agency", LAT, LON, "10", plumbing);
        double[] far = destination(LAT, LON, 90, 5.0);
        Tenant farther = createTenant("Far Agency", far[0], far[1], "20", plumbing);

        double[] inside = destination(LAT, LON, 270, 9.99);
        double[] outside = destination(LAT, LON, 270, 10.01);

        assertThat(service.covering(inside[0], inside[1], plumbing)).extracting(CoveringTenant::tenantId)
                .containsExactly(near.getId(), farther.getId());
        assertThat(service.covering(outside[0], outside[1], plumbing)).extracting(CoveringTenant::tenantId)
                .containsExactly(farther.getId());
        assertThat(service.covering(LAT, LON, plumbing).get(0).distanceKm()).isZero();
    }

    @Test
    void covering_excludesSuspendedTenantsAndOtherCategories() {
        Tenant suspended = createTenant("Sleepy Agency", LAT, LON, "20", plumbing);
        service.update(suspended.getId(), new TenantCommand("Sleepy Agency", null, null, LAT, LON,
                new BigDecimal("20"), List.of(plumbing), "SUSPENDED"), platformAdmin);
        Tenant electricians = createTenant("Sparks", LAT, LON, "20", electrical);

        assertThat(service.covering(LAT, LON, plumbing)).isEmpty();
        assertThat(service.covering(LAT, LON, electrical)).extracting(CoveringTenant::tenantId)
                .containsExactly(electricians.getId());
    }

    @Test
    void covering_refusesOutOfRangeCoordinates() {
        assertRefused(() -> service.covering(91, LON, plumbing), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertRefused(() -> service.covering(LAT, 181, plumbing), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    }

    /**
     * Property MT2 over generated Tenants and booking points just inside and just outside each
     * Tenant's circle: a Tenant is returned if and only if it is {@code ACTIVE}, serves the
     * category, and lies within its radius by haversine distance.
     */
    @Test
    void covering_matchesTheDefinitionForGeneratedTenantsAndBoundaryPoints() {
        Random random = new Random(20261003L);
        List<Tenant> created = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double lat = LAT + (random.nextDouble() - 0.5) * 2.0;
            double lon = LON + (random.nextDouble() - 0.5) * 2.0;
            String radius = String.valueOf(1 + random.nextInt(100));
            UUID category = random.nextBoolean() ? plumbing : electrical;
            Tenant t = createTenant("Agency " + i, lat, lon, radius, category);
            if (random.nextInt(4) == 0) {
                service.update(t.getId(), new TenantCommand(t.getName(), null, null, lat, lon,
                        new BigDecimal(radius), List.of(category), "SUSPENDED"), platformAdmin);
            }
            created.add(tenants.findById(t.getId()).orElseThrow());
        }

        List<double[]> points = new ArrayList<>();
        for (Tenant t : created) {
            double r = t.getServiceRadiusKm().doubleValue();
            double bearing = random.nextDouble() * 360;
            points.add(destination(t.getBaseLatitude(), t.getBaseLongitude(), bearing, r - 0.01));
            points.add(destination(t.getBaseLatitude(), t.getBaseLongitude(), bearing, r + 0.01));
        }

        for (double[] point : points) {
            for (UUID category : List.of(plumbing, electrical)) {
                Set<UUID> expected = new HashSet<>();
                for (Tenant t : created) {
                    double d = GeoMath.haversineKm(point[0], point[1], t.getBaseLatitude(), t.getBaseLongitude());
                    if (t.getStatus() == TenantStatus.ACTIVE && t.getCategoryIds().contains(category)
                            && d <= t.getServiceRadiusKm().doubleValue()) {
                        expected.add(t.getId());
                    }
                }
                List<CoveringTenant> actual = service.covering(point[0], point[1], category);
                assertThat(actual).extracting(CoveringTenant::tenantId).containsExactlyInAnyOrderElementsOf(expected);
                assertThat(actual).extracting(CoveringTenant::distanceKm).isSorted();
            }
        }
    }

    /** The point {@code distanceKm} from the start along {@code bearingDeg}, on GeoMath's sphere. */
    private static double[] destination(double lat, double lon, double bearingDeg, double distanceKm) {
        double delta = distanceKm / GeoMath.EARTH_RADIUS_KM;
        double theta = Math.toRadians(bearingDeg);
        double phi1 = Math.toRadians(lat);
        double lambda1 = Math.toRadians(lon);
        double phi2 = Math.asin(Math.sin(phi1) * Math.cos(delta)
                + Math.cos(phi1) * Math.sin(delta) * Math.cos(theta));
        double lambda2 = lambda1 + Math.atan2(Math.sin(theta) * Math.sin(delta) * Math.cos(phi1),
                Math.cos(delta) - Math.sin(phi1) * Math.sin(phi2));
        return new double[] {Math.toDegrees(phi2), Math.toDegrees(lambda2)};
    }

    // ============================= Administrators (Requirement MT-2) ====================

    @Test
    void addAdmin_grantsTheRoleThenRecordsTheMembership() {
        Tenant t = createTenant("Ara Home Services", LAT, LON, "20", plumbing);
        UUID user = auth.register("+919000000031");
        auth.onGrant = id -> assertThat(admins.findById(id)).as("granted before recording").isEmpty();

        AdminView added = service.addAdmin(t.getId(), " +91 90000-00031 ", platformAdmin);

        assertThat(added).isEqualTo(new AdminView(user, "+919000000031"));
        assertThat(admins.findById(user)).map(TenantAdmin::getTenantId).contains(t.getId());
        assertThat(auth.calls).containsExactly("grant " + user);
        assertThat(service.admins(t.getId())).containsExactly(new AdminView(user, "+919000000031"));
    }

    @Test
    void addAdmin_unknownNumberIs404AndChangesNothing() {
        Tenant t = createTenant("Ara Home Services", LAT, LON, "20", plumbing);

        assertRefused(() -> service.addAdmin(t.getId(), "+919999999999", platformAdmin),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        assertRefused(() -> service.addAdmin(UUID.randomUUID(), "+919999999999", platformAdmin),
                HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND");
        assertThat(auth.calls).isEmpty();
        assertThat(admins.count()).isZero();
    }

    @Test
    void addAdmin_aUserAdministersAtMostOneTenant() {
        Tenant a = createTenant("Alpha Agency", LAT, LON, "20", plumbing);
        Tenant b = createTenant("Beta Agency", LAT, LON, "20", plumbing);
        UUID user = auth.register("+919000000031");
        service.addAdmin(a.getId(), "+919000000031", platformAdmin);
        auth.calls.clear();

        assertRefused(() -> service.addAdmin(b.getId(), "+919000000031", platformAdmin),
                HttpStatus.CONFLICT, "ADMIN_OF_OTHER_TENANT");
        assertThat(auth.calls).as("no grant for a refused add").isEmpty();
        assertThat(admins.findById(user)).map(TenantAdmin::getTenantId).contains(a.getId());

        // Re-adding to the same Tenant is idempotent.
        assertThat(service.addAdmin(a.getId(), "+919000000031", platformAdmin).userId()).isEqualTo(user);
        assertThat(admins.count()).isEqualTo(1);
    }

    /** Property MT8 under a lost race: another Tenant records the same user between grant and insert. */
    @Test
    void addAdmin_losingTheRaceToAnotherTenantIs409AndKeepsTheWinnersRole() {
        Tenant a = createTenant("Alpha Agency", LAT, LON, "20", plumbing);
        Tenant b = createTenant("Beta Agency", LAT, LON, "20", plumbing);
        UUID user = auth.register("+919000000031");
        auth.onGrant = id -> admins.saveAndFlush(new TenantAdmin(b.getId(), id));

        assertRefused(() -> service.addAdmin(a.getId(), "+919000000031", platformAdmin),
                HttpStatus.CONFLICT, "ADMIN_OF_OTHER_TENANT");

        assertThat(admins.findById(user)).map(TenantAdmin::getTenantId).contains(b.getId());
        assertThat(auth.calls).containsExactly("grant " + user);
    }

    @Test
    void addAdmin_authOutageIs503AndRecordsNothing() {
        Tenant t = createTenant("Ara Home Services", LAT, LON, "20", plumbing);
        auth.register("+919000000031");
        auth.grantFailure = new ProviderException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE", "down");

        assertRefused(() -> service.addAdmin(t.getId(), "+919000000031", platformAdmin),
                HttpStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE");
        assertThat(admins.count()).isZero();
    }

    @Test
    void removeAdmin_revokesTheRoleAndDeletesTheMembership() {
        Tenant t = createTenant("Ara Home Services", LAT, LON, "20", plumbing);
        UUID user = auth.register("+919000000031");
        service.addAdmin(t.getId(), "+919000000031", platformAdmin);
        auth.calls.clear();

        service.removeAdmin(t.getId(), user, platformAdmin);

        assertThat(admins.findById(user)).isEmpty();
        assertThat(auth.calls).containsExactly("revoke " + user);
    }

    @Test
    void removeAdmin_ofAnotherTenantsAdminIs404AndRevokesNothing() {
        Tenant a = createTenant("Alpha Agency", LAT, LON, "20", plumbing);
        Tenant b = createTenant("Beta Agency", LAT, LON, "20", plumbing);
        UUID user = auth.register("+919000000031");
        service.addAdmin(a.getId(), "+919000000031", platformAdmin);
        auth.calls.clear();

        assertRefused(() -> service.removeAdmin(b.getId(), user, platformAdmin),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        assertThat(admins.findById(user)).isPresent();
        assertThat(auth.calls).isEmpty();
    }

    @Test
    void removeAdmin_whenTheRevokeFails_keepsTheMembershipSoARetryCanFinish() {
        Tenant t = createTenant("Ara Home Services", LAT, LON, "20", plumbing);
        UUID user = auth.register("+919000000031");
        service.addAdmin(t.getId(), "+919000000031", platformAdmin);
        auth.revokeFailure = new ProviderException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE", "down");

        assertRefused(() -> service.removeAdmin(t.getId(), user, platformAdmin),
                HttpStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE");
        assertThat(admins.findById(user)).map(TenantAdmin::getTenantId).contains(t.getId());

        auth.revokeFailure = null;
        service.removeAdmin(t.getId(), user, platformAdmin);
        assertThat(admins.findById(user)).isEmpty();
    }

    // ============================= Tenant Portal context (Requirements MT-1.4, MT-10.1) =

    @Test
    void requirePortalTenant_resolvesFromTheCallerOnly() {
        Tenant t = createTenant("Ara Home Services", LAT, LON, "20", plumbing);
        UUID user = auth.register("+919000000031");
        service.addAdmin(t.getId(), "+919000000031", platformAdmin);

        assertThat(service.requirePortalTenant(user).getId()).isEqualTo(t.getId());
        assertRefused(() -> service.requirePortalTenant(UUID.randomUUID()), HttpStatus.NOT_FOUND,
                "TENANT_NOT_FOUND");

        service.update(t.getId(), new TenantCommand("Ara Home Services", null, null, LAT, LON,
                new BigDecimal("20"), List.of(plumbing), "SUSPENDED"), platformAdmin);
        assertRefused(() -> service.requirePortalTenant(user), HttpStatus.FORBIDDEN, "TENANT_SUSPENDED");
        // The internal lookup still answers, with the status, so booking-service can refuse too.
        assertThat(service.byAdmin(user)).map(Tenant::getStatus).contains(TenantStatus.SUSPENDED);
    }

    // ============================= Team (Requirement MT-3) ==============================

    @Test
    void addProvider_attachesByMobileNumberAndReportsTheMember() {
        Tenant t = createTenant("Ara Home Services", LAT, LON, "20", plumbing);
        ProviderProfile ravi = persistProvider("+919000000011", "Ravi Kumar", "plumbing", "drains");
        verification.statuses.put(ravi.getId(), "APPROVED");

        TeamProviderView added = team.addProvider(t.getId(), "+919000000011", platformAdmin);

        assertThat(added.providerId()).isEqualTo(ravi.getId());
        assertThat(added.mobileNumber()).isEqualTo("+919000000011");
        assertThat(added.primarySkill()).isEqualTo("plumbing");
        assertThat(added.assignable()).isTrue();
        assertThat(profiles.findById(ravi.getId()).orElseThrow().getTenantId()).isEqualTo(t.getId());
        assertThat(service.ofProvider(ravi.getId())).map(Tenant::getId).contains(t.getId());

        // Idempotent for the same Tenant.
        assertThat(team.addProvider(t.getId(), "+919000000011", platformAdmin).providerId()).isEqualTo(ravi.getId());
    }

    @Test
    void addProvider_requiresAProviderAccountWithAProfile() {
        Tenant t = createTenant("Ara Home Services", LAT, LON, "20", plumbing);
        auth.register("+919000000021", "CUSTOMER");
        auth.register("+919000000022", AuthUserClientPort.SERVICE_PROVIDER); // no profile

        for (String mobile : List.of("+919000000021", "+919000000022", "+919999999999")) {
            assertRefused(() -> team.addProvider(t.getId(), mobile, platformAdmin),
                    HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND");
        }
        assertRefused(() -> team.addProvider(UUID.randomUUID(), "+919000000021", platformAdmin),
                HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND");
    }

    /** Property MT8: a provider is on at most one Tenant's team. */
    @Test
    void addProvider_aProviderBelongsToAtMostOneTenant() {
        Tenant a = createTenant("Alpha Agency", LAT, LON, "20", plumbing);
        Tenant b = createTenant("Beta Agency", LAT, LON, "20", plumbing);
        ProviderProfile ravi = persistProvider("+919000000011", "Ravi Kumar", "plumbing");
        team.addProvider(a.getId(), "+919000000011", platformAdmin);

        assertRefused(() -> team.addProvider(b.getId(), "+919000000011", platformAdmin),
                HttpStatus.CONFLICT, "PROVIDER_IN_OTHER_TENANT");
        assertThat(profiles.attachToTenant(ravi.getId(), b.getId())).isZero();
        assertThat(profiles.findById(ravi.getId()).orElseThrow().getTenantId()).isEqualTo(a.getId());
    }

    /** Property MT5: a Tenant cannot release or inspect another Tenant's provider. */
    @Test
    void removeProvider_andMembership_areScopedToTheTenant() {
        Tenant a = createTenant("Alpha Agency", LAT, LON, "20", plumbing);
        Tenant b = createTenant("Beta Agency", LAT, LON, "20", plumbing);
        ProviderProfile ravi = persistProvider("+919000000011", "Ravi Kumar", "plumbing");
        team.addProvider(a.getId(), "+919000000011", platformAdmin);

        assertRefused(() -> team.removeProvider(b.getId(), ravi.getId(), platformAdmin),
                HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND");
        assertThat(team.membership(b.getId(), ravi.getId())).isEmpty();
        assertThat(team.team(b.getId())).isEmpty();
        assertThat(profiles.findById(ravi.getId()).orElseThrow().getTenantId()).isEqualTo(a.getId());

        team.removeProvider(a.getId(), ravi.getId(), platformAdmin);
        assertThat(profiles.findById(ravi.getId()).orElseThrow().getTenantId()).isNull();
        assertThat(service.ofProvider(ravi.getId())).isEmpty();
        assertRefused(() -> team.removeProvider(a.getId(), ravi.getId(), platformAdmin),
                HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND");
    }

    @Test
    void team_showsAvailabilityAndAssignability() {
        Tenant t = createTenant("Ara Home Services", LAT, LON, "20", plumbing);
        ProviderProfile approved = persistProvider("+919000000011", "Asha", "electrical");
        approved.replaceAvailability(List.of(new AvailabilitySlot(DayOfWeek.MONDAY, 10, 14)));
        profiles.saveAndFlush(approved);
        ProviderProfile offShift = persistProvider("+919000000012", "Bala", "plumbing");
        offShift.replaceAvailability(List.of(new AvailabilitySlot(DayOfWeek.TUESDAY, 10, 14)));
        profiles.saveAndFlush(offShift);
        ProviderProfile underReview = persistProvider("+919000000013", "Chetan", "plumbing");
        underReview.applyAggregateRating(new BigDecimal("2.0"), new BigDecimal("3.0"));
        profiles.saveAndFlush(underReview);
        ProviderProfile pending = persistProvider("+919000000014", "Divya", "plumbing");
        for (ProviderProfile p : List.of(approved, offShift, underReview, pending)) {
            profiles.attachToTenant(p.getId(), t.getId());
        }
        verification.statuses.put(approved.getId(), "APPROVED");
        verification.statuses.put(offShift.getId(), "APPROVED");
        verification.statuses.put(underReview.getId(), "APPROVED");
        verification.statuses.put(pending.getId(), "DOCUMENT_SUBMITTED");

        List<TeamProviderView> list = team.team(t.getId());

        assertThat(list).extracting(TeamProviderView::displayName).containsExactly("Asha", "Bala", "Chetan", "Divya");
        assertThat(list).extracting(TeamProviderView::availableNow).containsExactly(true, false, true, true);
        assertThat(list).extracting(TeamProviderView::assignable).containsExactly(true, true, false, false);
        assertThat(list).extracting(TeamProviderView::verificationStatus)
                .containsExactly("APPROVED", "APPROVED", "APPROVED", "DOCUMENT_SUBMITTED");
        assertThat(list).extracting(TeamProviderView::mobileNumber).containsOnlyNulls();
        assertThat(verification.lookups).as("one batch lookup").isEqualTo(1);

        assertThat(team.membership(t.getId(), approved.getId()))
                .contains(new MembershipView(true, true, "APPROVED"));
        assertThat(team.membership(t.getId(), underReview.getId()))
                .contains(new MembershipView(true, false, "APPROVED"));
        assertThat(team.membership(t.getId(), pending.getId()))
                .contains(new MembershipView(true, false, "DOCUMENT_SUBMITTED"));
    }

    @Test
    void verificationOutage_makesNobodyAssignable() {
        Tenant t = createTenant("Ara Home Services", LAT, LON, "20", plumbing);
        ProviderProfile ravi = persistProvider("+919000000011", "Ravi", "plumbing");
        profiles.attachToTenant(ravi.getId(), t.getId());
        verification.statuses.put(ravi.getId(), "APPROVED");
        verification.available = false;

        assertThat(team.team(t.getId())).singleElement()
                .satisfies(v -> {
                    assertThat(v.verificationStatus()).isNull();
                    assertThat(v.assignable()).isFalse();
                });
        assertThat(team.membership(t.getId(), ravi.getId())).contains(new MembershipView(true, false, null));
    }

    /** The aggregate never writes tenant_id, so a profile saved from a stale copy keeps the membership. */
    @Test
    void savingAProfileLoadedBeforeTheProviderJoinedKeepsTheMembership() {
        Tenant t = createTenant("Ara Home Services", LAT, LON, "20", plumbing);
        ProviderProfile ravi = persistProvider("+919000000011", "Ravi", "plumbing");
        ProviderProfile loadedBefore = profiles.findById(ravi.getId()).orElseThrow();

        team.addProvider(t.getId(), "+919000000011", platformAdmin);
        loadedBefore.setDisplayName("Ravi Kumar");
        profiles.saveAndFlush(loadedBefore);

        ProviderProfile now = profiles.findById(ravi.getId()).orElseThrow();
        assertThat(now.getDisplayName()).isEqualTo("Ravi Kumar");
        assertThat(now.getTenantId()).isEqualTo(t.getId());
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = ProviderProfile.class)
    @EnableJpaRepositories(basePackageClasses = ProviderProfileRepository.class)
    static class JpaConfig {
    }
}
