package com.homefix.shared.config;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.ClassUtils;

/**
 * Loads the {@code config.app_config} table into the environment, so any Spring property can be
 * set from the database: the row's {@code config_key} is the property name and its
 * {@code config_value} the value, e.g. {@code homefix.apps.admin-portal-url}.
 *
 * <p>The rows are added as the property source {@value #PROPERTY_SOURCE_NAME} directly after the
 * system environment: command-line arguments, system properties and environment variables still
 * win, and the table wins over the service's YAML. Rows are read once, at startup; restart the
 * deployable after changing one.
 *
 * <p>The table is read with the service's own {@code spring.datasource.*} settings, before the
 * application context (and so before Flyway) exists. When it cannot be read (it does not exist
 * yet on a fresh database, or the database is down) the service starts on its YAML defaults and
 * logs why. {@code homefix.app-config.enabled=false} skips the table entirely (the tests do).
 */
public class AppConfigEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String PROPERTY_SOURCE_NAME = "homefixAppConfig";

    static final String ENABLED = "homefix.app-config.enabled";
    static final String QUERY = "SELECT config_key, config_value FROM config.app_config";

    /** "undefined_table" in PostgreSQL, "table not found" in H2. */
    private static final String[] TABLE_MISSING = {"42P01", "42S02", "42S04"};
    private static final int TIMEOUT_SECONDS = 10;

    private final Log log;

    public AppConfigEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(AppConfigEnvironmentPostProcessor.class);
    }

    /** After the config files are loaded, which is where {@code spring.datasource.*} comes from. */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty(ENABLED, Boolean.class, true)) {
            return;
        }
        String url;
        Properties credentials = new Properties();
        String driverClass;
        try {
            url = environment.getProperty("spring.datasource.url");
            putIfPresent(credentials, "user", environment.getProperty("spring.datasource.username"));
            putIfPresent(credentials, "password", environment.getProperty("spring.datasource.password"));
            driverClass = environment.getProperty("spring.datasource.driver-class-name");
        } catch (IllegalArgumentException unresolved) {
            log.warn("Not reading config.app_config: the datasource settings are incomplete ("
                    + unresolved.getMessage() + ").");
            return;
        }
        if (url == null || url.isBlank()) {
            return;
        }
        Map<String, Object> rows;
        try {
            rows = read(url, credentials, driverClass);
        } catch (SQLException e) {
            if (isTableMissing(e)) {
                log.info("config.app_config does not exist yet (Flyway creates it); using the YAML defaults.");
            } else {
                log.warn("Could not read config.app_config (" + e.getMessage() + "); using the YAML defaults.");
            }
            return;
        } catch (ReflectiveOperationException | LinkageError e) {
            log.warn("Could not load the JDBC driver for " + url + " (" + e + "); not reading config.app_config.");
            return;
        }
        MutablePropertySources sources = environment.getPropertySources();
        MapPropertySource source = new MapPropertySource(PROPERTY_SOURCE_NAME, rows);
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
        } else {
            sources.addFirst(source);
        }
        log.info("Loaded " + rows.size() + " setting(s) from config.app_config.");
    }

    private static Map<String, Object> read(String url, Properties credentials, String driverClass)
            throws SQLException, ReflectiveOperationException {
        if (driverClass == null || driverClass.isBlank()) {
            driverClass = DatabaseDriver.fromJdbcUrl(url).getDriverClassName();
        }
        if (driverClass == null) {
            throw new SQLException("No JDBC driver is known for " + url);
        }
        Properties properties = new Properties();
        properties.putAll(credentials);
        if (url.startsWith("jdbc:postgresql:")) {
            // Fail fast rather than hold up startup when the database is unreachable.
            properties.setProperty("connectTimeout", String.valueOf(TIMEOUT_SECONDS));
            properties.setProperty("socketTimeout", String.valueOf(TIMEOUT_SECONDS));
        }
        Driver driver = (Driver) ClassUtils.forName(driverClass, AppConfigEnvironmentPostProcessor.class.getClassLoader())
                .getDeclaredConstructor().newInstance();
        Map<String, Object> rows = new LinkedHashMap<>();
        try (Connection connection = driver.connect(url, properties)) {
            if (connection == null) {
                throw new SQLException(driverClass + " does not accept " + url);
            }
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(QUERY)) {
                while (resultSet.next()) {
                    rows.put(resultSet.getString(1), resultSet.getString(2));
                }
            }
        }
        return rows;
    }

    private static boolean isTableMissing(SQLException e) {
        for (String state : TABLE_MISSING) {
            if (state.equals(e.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static void putIfPresent(Properties properties, String key, String value) {
        if (value != null) {
            properties.setProperty(key, value);
        }
    }
}
