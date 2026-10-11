package com.homefix.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class AppConfigEnvironmentPostProcessorTest {

    private final AppConfigEnvironmentPostProcessor processor =
            new AppConfigEnvironmentPostProcessor(new DeferredLogs());

    @Test
    void rowsOverrideTheYamlButNotTheEnvironment() throws SQLException {
        String url = database();
        execute(url, "INSERT INTO config.app_config (config_key, config_value) VALUES "
                + "('homefix.apps.admin-portal-url', 'https://admin.example'), "
                + "('homefix.apps.provider-app-url', 'https://pro.example')");
        StandardEnvironment environment = environment(url, Map.of(
                "homefix.apps.admin-portal-url", "http://localhost:5175",
                "homefix.apps.provider-app-url", "http://localhost:5174"));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("HOMEFIX_APPS_PROVIDERAPPURL", "https://from-env.example")));

        processor.postProcessEnvironment(environment, new SpringApplication());
        // Read it as a Boot application does: with relaxed names for the environment variables.
        ConfigurationPropertySources.attach(environment);

        assertThat(environment.getProperty("homefix.apps.admin-portal-url")).isEqualTo("https://admin.example");
        assertThat(environment.getProperty("homefix.apps.provider-app-url")).isEqualTo("https://from-env.example");
    }

    @Test
    void aMissingTableLeavesTheEnvironmentAlone() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        StandardEnvironment environment = environment(url, Map.of("homefix.apps.admin-portal-url", "http://localhost:5175"));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources().contains(AppConfigEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
        assertThat(environment.getProperty("homefix.apps.admin-portal-url")).isEqualTo("http://localhost:5175");
    }

    @Test
    void anUnresolvablePasswordOrNoDatasourceIsSkipped() {
        StandardEnvironment noPassword = environment("jdbc:h2:mem:unused", Map.of());
        noPassword.getPropertySources().addFirst(new MapPropertySource("secret",
                Map.of("spring.datasource.password", "${DB_PASSWORD_NOT_SET}")));
        StandardEnvironment noDatasource = new StandardEnvironment();

        processor.postProcessEnvironment(noPassword, new SpringApplication());
        processor.postProcessEnvironment(noDatasource, new SpringApplication());

        assertThat(noPassword.getPropertySources().contains(AppConfigEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
        assertThat(noDatasource.getPropertySources().contains(AppConfigEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }

    @Test
    void canBeTurnedOff() throws SQLException {
        String url = database();
        execute(url, "INSERT INTO config.app_config (config_key, config_value) VALUES ('a.b', 'from-db')");
        StandardEnvironment environment = environment(url, Map.of(AppConfigEnvironmentPostProcessor.ENABLED, "false"));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("a.b")).isNull();
    }

    /** An H2 database with the table as the migration creates it (H2 has no advisory locks). */
    static String database() throws SQLException {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        execute(url, "CREATE SCHEMA config");
        execute(url, "CREATE TABLE config.app_config (config_key varchar(200) PRIMARY KEY, "
                + "config_value text NOT NULL, exposed_to_clients boolean NOT NULL DEFAULT false, "
                + "description varchar(500), updated_at timestamp with time zone NOT NULL DEFAULT now())");
        return url;
    }

    static void execute(String url, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** The YAML values as the config files would have loaded them, plus the datasource. */
    private static StandardEnvironment environment(String url, Map<String, Object> yaml) {
        Map<String, Object> config = new HashMap<>(yaml);
        config.put("spring.datasource.url", url);
        config.put("spring.datasource.username", "sa");
        config.put("spring.datasource.password", "");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("Config resource 'application.yml'", config));
        return environment;
    }
}
