package com.homefix.gateway.waf;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure request-inspection engine that recognises common OWASP Top 10 attack payloads —
 * specifically SQL injection and cross-site scripting (XSS) — in request-supplied strings
 * (Requirement 23.6).
 *
 * <p>In production the authoritative WAF is AWS WAF with the OWASP managed rule set attached to the
 * API Gateway (provisioned in Task 1). This matcher provides an application-layer, defence-in-depth
 * inspection that is fully unit-testable and lets Property-27-style tests target the classifier
 * directly with no infrastructure. It has no Spring/servlet dependency by design.
 *
 * <p>The matcher errs toward the payloads used by the task's test corpus and canonical OWASP
 * examples. It is deliberately conservative: matching returns a coarse {@link Category} only, and
 * the caller returns a <em>generic</em> 400 body so no internal detail (which rule matched, the
 * offending value) leaks to clients (Requirement 23.6, Error-Handling principle 2).
 */
public final class AttackPatternMatcher {

    /** Classification of a matched attack payload. */
    public enum Category {
        SQL_INJECTION,
        XSS
    }

    // --- SQL injection signatures -------------------------------------------------------------
    // Classic tautologies (' OR 1=1 --), UNION SELECT exfiltration, stacked/terminating comments,
    // and dangerous keywords combined with SQL syntax.
    private static final List<Pattern> SQLI_PATTERNS = List.of(
            Pattern.compile("('|%27|\")\\s*(or|and)\\s+.*(=|like)", flags()),
            Pattern.compile("\\b(or|and)\\b\\s+\\d+\\s*=\\s*\\d+", flags()),
            Pattern.compile("\\bunion\\b\\s+(all\\s+)?\\bselect\\b", flags()),
            Pattern.compile("\\bselect\\b.+\\bfrom\\b", flags()),
            Pattern.compile("\\b(insert\\s+into|delete\\s+from|update\\s+.+\\bset\\b)\\b", flags()),
            Pattern.compile("\\b(drop|truncate|alter)\\s+(table|database)\\b", flags()),
            Pattern.compile("(;|\\b)\\s*(exec|execute)\\s*\\(", flags()),
            Pattern.compile("(--|#|/\\*)", flags()),
            Pattern.compile("'\\s*;\\s*", flags())
    );

    // --- XSS signatures -----------------------------------------------------------------------
    private static final List<Pattern> XSS_PATTERNS = List.of(
            Pattern.compile("<\\s*script\\b", flags()),
            Pattern.compile("</\\s*script\\s*>", flags()),
            Pattern.compile("javascript:", flags()),
            Pattern.compile("on(error|load|click|mouseover|focus)\\s*=", flags()),
            Pattern.compile("<\\s*(img|svg|iframe|body|object|embed)\\b[^>]*(on\\w+|src\\s*=)", flags()),
            Pattern.compile("(alert|prompt|confirm|eval)\\s*\\(", flags()),
            Pattern.compile("document\\.(cookie|location|write)", flags())
    );

    private static int flags() {
        return Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    }

    /**
     * Inspects a single value. Returns the matched {@link Category}, or {@code null} when the value
     * is benign. The value is URL-decoded once before matching to defeat trivial percent-encoding
     * evasion.
     */
    public Category classify(String rawValue) {
        if (rawValue == null || rawValue.isEmpty()) {
            return null;
        }
        String normalised = normalise(rawValue);

        for (Pattern p : SQLI_PATTERNS) {
            if (p.matcher(normalised).find()) {
                return Category.SQL_INJECTION;
            }
        }
        for (Pattern p : XSS_PATTERNS) {
            if (p.matcher(normalised).find()) {
                return Category.XSS;
            }
        }
        return null;
    }

    /** Convenience predicate: {@code true} when the value matches any known attack signature. */
    public boolean isMalicious(String rawValue) {
        return classify(rawValue) != null;
    }

    /**
     * Inspects every supplied value (e.g. all query-parameter values and header values) and returns
     * the first matched category, or {@code null} when all values are benign.
     */
    public Category classifyAny(Iterable<String> values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            Category c = classify(v);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private String normalise(String value) {
        String decoded = value;
        try {
            decoded = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // Malformed encoding — inspect the raw value as-is rather than rejecting outright here.
        }
        return decoded.toLowerCase(Locale.ROOT);
    }
}
