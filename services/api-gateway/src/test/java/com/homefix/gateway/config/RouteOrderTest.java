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
            Map<String, Object> gateway = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                    root.get("spring")).get("cloud")).get("gateway");
            List<Map<String, Object>> routes = (List<Map<String, Object>>) gateway.get("routes");
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
    }
}
