package com.homefix.admin.audit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Derives the one-line, human-readable change summary the Admin Portal's Audit Logs view shows
 * from an entry's stored before/after JSON (Requirement 19.8):
 *
 * <ul>
 *   <li>CREATE — {@code Created: discount=10, code=SAVE10}</li>
 *   <li>DELETE — {@code Deleted: code=SAVE10}</li>
 *   <li>UPDATE / APPROVE / REJECT — {@code status: ACTIVE → SUSPENDED; reason: (none) → fraud},
 *       listing only the fields whose value changed</li>
 * </ul>
 *
 * <p>With values hidden (the {@code audit.summaryShowsValues} setting) only field names appear,
 * e.g. {@code Changed: status, reason}. The full before/after values stay in the Audit_Log either
 * way. Summaries are capped at {@link #MAX_LENGTH} characters.
 */
public final class ChangeSummaries {

    static final int MAX_LENGTH = 300;
    private static final String NONE = "(none)";

    private ChangeSummaries() {
    }

    public static String summarise(AuditLogEntry entry, boolean showValues, ObjectMapper objectMapper) {
        ObjectNode before = parse(entry.getBeforeValues(), objectMapper);
        ObjectNode after = parse(entry.getAfterValues(), objectMapper);
        if ((entry.getBeforeValues() != null && before == null) || (entry.getAfterValues() != null && after == null)) {
            // Not a JSON object (written by hand or by an older version): show what is there.
            return truncate(Objects.toString(entry.getBeforeValues(), NONE) + " → "
                    + Objects.toString(entry.getAfterValues(), NONE));
        }
        String summary = switch (entry.getActionType()) {
            case CREATE -> snapshot("Created", after, showValues);
            case DELETE -> snapshot("Deleted", before, showValues);
            case UPDATE, APPROVE, REJECT -> changes(entry.getActionType(), before, after, showValues);
        };
        return truncate(summary);
    }

    private static String snapshot(String verb, ObjectNode values, boolean showValues) {
        if (values == null || values.isEmpty()) {
            return verb;
        }
        List<String> parts = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> fields = values.fields(); fields.hasNext(); ) {
            Map.Entry<String, JsonNode> field = fields.next();
            parts.add(showValues ? field.getKey() + "=" + text(field.getValue()) : field.getKey());
        }
        return verb + ": " + String.join(", ", parts);
    }

    private static String changes(AdminAction action, ObjectNode before, ObjectNode after, boolean showValues) {
        Set<String> names = new LinkedHashSet<>();
        if (before != null) {
            before.fieldNames().forEachRemaining(names::add);
        }
        if (after != null) {
            after.fieldNames().forEachRemaining(names::add);
        }
        List<String> parts = new ArrayList<>();
        for (String name : names) {
            JsonNode old = before == null ? null : before.get(name);
            JsonNode now = after == null ? null : after.get(name);
            if (Objects.equals(old, now)) {
                continue;
            }
            parts.add(showValues ? name + ": " + text(old) + " → " + text(now) : name);
        }
        String verb = action == AdminAction.UPDATE ? "Updated" : action == AdminAction.APPROVE ? "Approved" : "Rejected";
        if (parts.isEmpty()) {
            return verb + " (no field changes)";
        }
        return showValues ? String.join("; ", parts) : "Changed: " + String.join(", ", parts);
    }

    private static String text(JsonNode value) {
        if (value == null || value.isNull()) {
            return NONE;
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private static ObjectNode parse(String json, ObjectMapper objectMapper) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node instanceof ObjectNode object ? object : null;
        } catch (JsonProcessingException malformed) {
            return null;
        }
    }

    private static String truncate(String summary) {
        return summary.length() <= MAX_LENGTH ? summary : summary.substring(0, MAX_LENGTH - 1) + "…";
    }
}
