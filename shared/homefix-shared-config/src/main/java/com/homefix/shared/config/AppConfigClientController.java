package com.homefix.shared.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /config/client}: the {@code config.app_config} rows marked
 * {@code exposed_to_clients}, as a JSON object of key to value, for the web apps (no sign-in
 * needed, so only mark rows that are safe to publish). Read from the table on each request, so
 * an SQL change reaches the browsers within the cache lifetime, without a restart.
 */
@RestController
public class AppConfigClientController {

    static final String QUERY =
            "SELECT config_key, config_value FROM config.app_config WHERE exposed_to_clients ORDER BY config_key";

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final JdbcTemplate jdbcTemplate;

    public AppConfigClientController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/config/client")
    public ResponseEntity<Map<String, String>> clientConfig() {
        Map<String, String> values = new LinkedHashMap<>();
        jdbcTemplate.query(QUERY, rs -> {
            values.put(rs.getString(1), rs.getString(2));
        });
        return ResponseEntity.ok().cacheControl(CACHE).body(values);
    }
}
