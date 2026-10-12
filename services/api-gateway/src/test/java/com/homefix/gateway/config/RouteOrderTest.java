package com.homefix.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Spring Cloud Gateway matches routes in declaration order, so a broad route above a narrow one
 * silently swallows its traffic: {@code /tenant/**} above {@code /tenant/bookings/**} would send a
 * Tenant's assignment queue to provider-service, and anything above the {@code /admin/**} catch-all
 * must stay above it. These are configuration facts no other test sees.
 */
class RouteOrderTest {

    private static List<String> routeIds;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadRoutes() throws Exception {
        try (InputStream in = RouteOrderTest.class.getResourceAsStream("/application.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            // Spring Cloud Gateway 2025: spring.cloud.gateway.server.webflux.routes.
            Map<String, Object> node = root;
            for (String key : List.of("spring", "cloud", "gateway", "server", "webflux")) {
                node = (Map<String, Object>) node.get(key);
            }
            List<Map<String, Object>> routes = (List<Map<String, Object>>) node.get("routes");
            routeIds = routes.stream().map(route -> (String) route.get("id")).toList();
        }
    }

    @Test
    void tenantBookingsAreMatchedBeforeTheTenantRoute() {
        assertThat(routeIds.indexOf("tenant-bookings")).isNotNegative()
                .isLessThan(routeIds.indexOf("tenant"));
    }

    @Test
    void everyAdminModuleRouteIsAboveTheAdminCatchAll() {
        int catchAll = routeIds.indexOf("admin-service");
        assertThat(catchAll).isEqualTo(routeIds.size() - 1);
        assertThat(routeIds.indexOf("admin-tenants")).isNotNegative().isLessThan(catchAll);
        assertThat(routeIds.indexOf("admin-invitations")).isNotNegative().isLessThan(catchAll);
    }

    @Test
    void agencyApplicationsHaveTheirOwnRoute() {
        // "/tenant-applications" is not under "/tenant/", so the tenant route never swallows it; it
        // must still exist, or the request falls through to no route at all.
        assertThat(routeIds).contains("tenant-applications");
    }
}
