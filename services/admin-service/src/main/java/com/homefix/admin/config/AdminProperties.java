package com.homefix.admin.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable Admin-service settings (Requirement 19).
 *
 * <p>Defaults match the acceptance criteria; values are overridable via configuration so
 * operations can tune behaviour without a code change.
 */
@ConfigurationProperties(prefix = "homefix.admin")
public class AdminProperties {

    /**
     * Dashboard metric refresh interval (Requirement 19.1). The cached snapshot served to Admins
     * is recomputed on this cadence. Defaults to 60 seconds.
     */
    private Duration dashboardRefreshInterval = Duration.ofSeconds(60);

    public Duration getDashboardRefreshInterval() {
        return dashboardRefreshInterval;
    }

    public void setDashboardRefreshInterval(Duration dashboardRefreshInterval) {
        this.dashboardRefreshInterval = dashboardRefreshInterval;
    }
}
