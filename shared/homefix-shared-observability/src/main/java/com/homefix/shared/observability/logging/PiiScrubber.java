package com.homefix.shared.observability.logging;

import java.util.regex.Pattern;

/**
 * Redacts personally identifiable information (PII) from free-text log messages
 * before they are written to any appender.
 *
 * <p>Covers the PII categories called out by Requirement 26.4:
 * <ul>
 *   <li>Email addresses</li>
 *   <li>Phone numbers (international and local formats)</li>
 *   <li>Payment card numbers (13–19 digits, optionally grouped)</li>
 *   <li>National ID numbers (Aadhaar-style 12-digit and SSN-style 9-digit)</li>
 *   <li>Values following sensitive keys such as {@code name=}, {@code address=},
 *       {@code password=}, {@code email=}, {@code phone=} in structured text</li>
 * </ul>
 *
 * <p>The scrubber is deliberately conservative: it favours over-masking to avoid
 * leaking PII. It is stateless and thread-safe.
 */
public final class PiiScrubber {

    /** Replacement token substituted for any detected PII. */
    public static final String MASK = "***";

    // Email: local@domain.tld
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    // Payment card: 13–19 digits, optionally separated by spaces or hyphens in groups.
    private static final Pattern CARD = Pattern.compile(
            "\\b(?:\\d[ -]?){13,19}\\b");

    // Aadhaar-style 12-digit national ID (grouped 4-4-4 or contiguous).
    private static final Pattern AADHAAR = Pattern.compile(
            "\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b");

    // US SSN-style 9-digit id: 3-2-4.
    private static final Pattern SSN = Pattern.compile(
            "\\b\\d{3}-\\d{2}-\\d{4}\\b");

    // Phone (two shapes, evaluated together):
    //  1. an international number with a leading +country code, or
    //  2. a bare 10-digit number optionally split into 5+5 / 3-3-4 style groups.
    // Digit-group separators are limited to a single space or hyphen so booking
    // references like "BKG-2026-000123" (which contain letters / different grouping)
    // are not affected.
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w.+])(?:"
                    + "\\+\\d{1,3}[ -]?\\d{2,5}([ -]?\\d{2,5}){1,3}"   // +country grouped
                    + "|\\d{5}[ -]\\d{5}"                                // 5-5
                    + "|\\d{3}[ -]\\d{3}[ -]\\d{4}"                      // 3-3-4
                    + "|\\d{10}"                                          // contiguous 10
                    + ")(?![\\w.])");

    // key=value / key: value where key is a sensitive field name. The value runs to
    // the end of the segment but stops before the next "<word>=" / "<word>:" token so
    // adjacent sensitive pairs are each masked independently.
    private static final Pattern SENSITIVE_KV = Pattern.compile(
            "(?i)\\b(name|firstName|lastName|fullName|address|street|password|passwd|"
                    + "secret|token|email|phone|mobile|nationalId|aadhaar|ssn|pan)\\b"
                    + "\\s*[=:]\\s*\"?"
                    + "(?:(?!\\s+\\w+\\s*[=:])[^\",;}\\]])+"
                    + "\"?");

    private PiiScrubber() {
    }

    /**
     * Returns a copy of {@code input} with all detected PII replaced by {@link #MASK}.
     * A {@code null} input yields {@code null}.
     */
    public static String scrub(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String result = input;
        // Order matters: mask structured key=value first (captures names/addresses),
        // then the strongly-typed patterns.
        result = SENSITIVE_KV.matcher(result).replaceAll(m -> maskKeyValue(m.group()));
        result = EMAIL.matcher(result).replaceAll(MASK);
        result = SSN.matcher(result).replaceAll(MASK);
        result = AADHAAR.matcher(result).replaceAll(MASK);
        result = CARD.matcher(result).replaceAll(MASK);
        result = PHONE.matcher(result).replaceAll(MASK);
        return result;
    }

    /**
     * Preserves the sensitive key and its separator, masking only the value portion.
     * e.g. {@code "email=jo@x.com"} → {@code "email=***"}.
     */
    private static String maskKeyValue(String match) {
        int sepIdx = indexOfSeparator(match);
        if (sepIdx < 0) {
            return MASK;
        }
        return match.substring(0, sepIdx + 1) + MASK;
    }

    private static int indexOfSeparator(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' || c == ':') {
                return i;
            }
        }
        return -1;
    }
}
