package com.homefix.gateway.waf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link AttackPatternMatcher} — the application-layer WAF signature engine that
 * backs the {@link com.homefix.gateway.filter.WafInspectionGatewayFilter} (Requirement 23.6).
 *
 * <p>Confirms that the canonical OWASP SQL-injection and XSS payloads used by the Task 28 test
 * corpus are recognised (and classified), including percent-encoded evasion variants, while benign
 * request values are left untouched so legitimate traffic is never blocked.
 */
class AttackPatternMatcherTest {

    private final AttackPatternMatcher matcher = new AttackPatternMatcher();

    // ── SQL injection payloads must be blocked (Requirement 23.6) ─────────────────────────────
    @ParameterizedTest
    @ValueSource(strings = {
            "' OR 1=1 --",
            "' OR '1'='1",
            "1 OR 1=1",
            "admin'--",
            "'; DROP TABLE users; --",
            "1 UNION SELECT username, password FROM users",
            "SELECT * FROM accounts",
            "1); DELETE FROM bookings; --",
            "%27%20OR%201%3D1%20--"   // percent-encoded "' OR 1=1 --"
    })
    void classifiesSqlInjectionPayloads(String payload) {
        assertThat(matcher.classify(payload))
                .as("payload %s should be flagged as SQL injection", payload)
                .isEqualTo(AttackPatternMatcher.Category.SQL_INJECTION);
        assertThat(matcher.isMalicious(payload)).isTrue();
    }

    // ── XSS payloads must be blocked (Requirement 23.6) ───────────────────────────────────────
    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert('xss')</script>",
            "<script src=http://evil.example/x.js></script>",
            "<img src=x onerror=alert(1)>",
            "<svg onload=alert(1)>",
            "javascript:alert(document.cookie)",
            "<iframe src=javascript:alert(1)>",
            "%3Cscript%3Ealert(1)%3C%2Fscript%3E"   // percent-encoded <script>alert(1)</script>
    })
    void classifiesXssPayloads(String payload) {
        assertThat(matcher.classify(payload))
                .as("payload %s should be flagged as XSS", payload)
                .isEqualTo(AttackPatternMatcher.Category.XSS);
        assertThat(matcher.isMalicious(payload)).isTrue();
    }

    // ── Benign values must pass through untouched (no false positives) ────────────────────────
    @ParameterizedTest
    @ValueSource(strings = {
            "hello world",
            "booking-1234",
            "New Delhi",
            "customer@example.com",
            "A perfectly ordinary review: the plumber was on time and tidy.",
            "12345",
            "category=plumbing"
    })
    void allowsBenignValues(String value) {
        assertThat(matcher.classify(value))
                .as("benign value %s must not be flagged", value)
                .isNull();
        assertThat(matcher.isMalicious(value)).isFalse();
    }

    @Test
    void nullAndEmptyValuesAreBenign() {
        assertThat(matcher.classify(null)).isNull();
        assertThat(matcher.classify("")).isNull();
    }

    @Test
    void classifyAnyReturnsFirstMatchAcrossValues() {
        List<String> values = List.of("safe", "also-safe", "' OR 1=1 --");
        assertThat(matcher.classifyAny(values))
                .isEqualTo(AttackPatternMatcher.Category.SQL_INJECTION);
    }

    @Test
    void classifyAnyReturnsNullWhenAllBenign() {
        assertThat(matcher.classifyAny(List.of("safe", "also-safe"))).isNull();
        assertThat(matcher.classifyAny(null)).isNull();
    }
}
