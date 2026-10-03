package com.homefix.notification.domain;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code {{placeholder}}} vocabulary of the notification templates: which values each event
 * supplies, how they are rendered, and which a stored template may use.
 *
 * <p>{@link #values} is the single source of truth. The set an admin may use in an event's
 * template ({@link #allowedFor}) is derived from it, so a placeholder can only be offered if the
 * renderer actually fills it — an edit cannot introduce a {@code {{customerName}}} that would go
 * out to customers verbatim. Every value is a non-PII attribute (Requirement 26.4).
 */
public final class TemplatePlaceholders {

    /** A well-formed placeholder: {@code {{name}}}, whitespace inside the braces tolerated. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9]*)\\s*}}");

    private TemplatePlaceholders() {
    }

    /**
     * The placeholder values an event supplies, with the fallbacks the built-in text has always
     * used when an attribute is absent ("your booking", "updated").
     *
     * <p>{@code ProviderAssigned} supplies {@code tenantName}, the partner agency that assigned the
     * job (Requirement MT-5.3). The built-in text uses it only in the variant picked when the
     * event names an agency; if an admin puts it into the other variant, the platform is named
     * instead of sending the placeholder literally.
     */
    public static Map<String, String> values(NotificationEventType eventType, Map<String, String> attributes) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("bookingReference", attributes.getOrDefault("bookingReference", "your booking"));
        if (eventType == NotificationEventType.PROVIDER_ASSIGNED) {
            values.put("tenantName", attributes.getOrDefault("tenantName", "HomeFix"));
        }
        if (eventType == NotificationEventType.COMPLAINT_STATUS_CHANGED) {
            values.put("complaintStatus", humanise(attributes.getOrDefault("complaintStatus", "updated")));
        }
        return values;
    }

    /** The placeholder names a template for this event may use. */
    public static Set<String> allowedFor(NotificationEventType eventType) {
        return Set.copyOf(values(eventType, Map.of()).keySet());
    }

    /**
     * The problems with a template text for this event, empty when it is valid: placeholders the
     * event does not supply, and stray braces that would be sent literally.
     */
    public static Set<String> problems(NotificationEventType eventType, String text) {
        Set<String> problems = new LinkedHashSet<>();
        if (text == null) {
            return problems;
        }
        Set<String> allowed = allowedFor(eventType);
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!allowed.contains(name)) {
                problems.add("unknown placeholder {{" + name + "}}; allowed: "
                        + allowed.stream().sorted().map(a -> "{{" + a + "}}").toList());
            }
        }
        String remainder = PLACEHOLDER.matcher(text).replaceAll("");
        if (remainder.contains("{{") || remainder.contains("}}")) {
            problems.add("malformed placeholder: use {{name}}");
        }
        return problems;
    }

    /**
     * Substitutes the placeholders in one pass, so a value that itself contains braces is never
     * expanded again. A placeholder with no value is left as written.
     */
    public static String render(String template, Map<String, String> values) {
        return PLACEHOLDER.matcher(template).replaceAll(match -> {
            String value = values.get(match.group(1));
            return Matcher.quoteReplacement(value == null ? match.group() : value);
        });
    }

    /** {@code REFUND_FAILED} -> {@code refund failed}. Status names are enum constants, not PII. */
    private static String humanise(String status) {
        return status.replace('_', ' ').toLowerCase(Locale.ROOT);
    }
}
