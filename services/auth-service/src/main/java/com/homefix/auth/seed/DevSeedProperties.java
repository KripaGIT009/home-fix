package com.homefix.auth.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the local development account seeder.
 *
 * <p>Disabled by default, so an environment that does not explicitly ask for seed accounts
 * never gets them. The password has no default for the same reason every other secret in
 * this repository has none: a default here would be a real, working credential committed to
 * source, on accounts that hold staff roles.
 *
 * <pre>
 * homefix:
 *   auth:
 *     dev-seed:
 *       enabled: true
 *       password: ${DEV_SEED_PASSWORD}
 * </pre>
 */
@ConfigurationProperties(prefix = "homefix.auth.dev-seed")
public class DevSeedProperties {

    /** Whether to create the local test accounts at startup. Never enable outside dev. */
    private boolean enabled = false;

    /** The shared password given to every seeded account. Required when enabled. */
    private String password;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
