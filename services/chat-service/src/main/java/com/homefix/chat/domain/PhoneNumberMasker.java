package com.homefix.chat.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, dependency-free utility that masks phone numbers embedded in free-text chat messages so a
 * participant's personal mobile number is never exposed in the chat interface (Requirement 18.8).
 *
 * <p>Kept free of Spring and I/O so it can be exhaustively unit-tested and targeted by a future
 * property-based test. The masker is deliberately conservative: it detects sequences that look
 * like phone numbers (optionally with a leading {@code +} country code, and interspersed spaces,
 * hyphens, or dots) and replaces every digit with {@code *}, preserving the surrounding
 * punctuation and any leading {@code +}.
 */
public final class PhoneNumberMasker {

    /**
     * Matches candidate phone numbers: an optional leading {@code +}, then 8–15 digits that may be
     * separated by single spaces, hyphens, or dots. The 8-digit floor avoids masking short
     * quantities (e.g. prices, quantities) while still catching national and E.164 numbers.
     */
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "\\+?\\d(?:[ .-]?\\d){7,14}");

    private PhoneNumberMasker() {
    }

    /**
     * Returns {@code text} with every phone-number-like substring masked. Non-null input always
     * yields non-null output; {@code null} yields {@code null}.
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = PHONE_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(maskDigits(matcher.group())));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Replaces each digit with {@code *}, leaving separators and a leading {@code +} intact. */
    private static String maskDigits(String candidate) {
        StringBuilder masked = new StringBuilder(candidate.length());
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            masked.append(Character.isDigit(c) ? '*' : c);
        }
        return masked.toString();
    }
}
