package com.homefix.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The one-line change summaries shown in the Audit Logs view (Requirement 19.8).
 */
class ChangeSummariesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static AuditLogEntry entry(AdminAction action, String before, String after) {
        return new AuditLogEntry(UUID.randomUUID(), UUID.randomUUID(), action, "USER", "u-1",
                before, after, Instant.parse("2026-10-01T00:00:00Z"));
    }

    private String summary(AuditLogEntry entry, boolean showValues) {
        return ChangeSummaries.summarise(entry, showValues, objectMapper);
    }

    @Test
    void updateListsOnlyTheFieldsThatChanged() {
        AuditLogEntry update = entry(AdminAction.UPDATE,
                "{\"status\":\"ACTIVE\",\"name\":\"Asha\",\"reason\":null}",
                "{\"status\":\"SUSPENDED\",\"name\":\"Asha\",\"reason\":\"fraud\"}");

        assertThat(summary(update, true)).isEqualTo("status: ACTIVE → SUSPENDED; reason: (none) → fraud");
        assertThat(summary(update, false)).isEqualTo("Changed: status, reason");
    }

    @Test
    void createAndDeleteListTheSnapshot() {
        assertThat(summary(entry(AdminAction.CREATE, null, "{\"code\":\"SAVE10\",\"discount\":10}"), true))
                .isEqualTo("Created: code=SAVE10, discount=10");
        assertThat(summary(entry(AdminAction.DELETE, "{\"code\":\"SAVE10\"}", null), false))
                .isEqualTo("Deleted: code");
        assertThat(summary(entry(AdminAction.CREATE, null, null), true)).isEqualTo("Created");
    }

    @Test
    void approvalWithIdenticalValuesSaysSo() {
        assertThat(summary(entry(AdminAction.APPROVE, "{\"a\":1}", "{\"a\":1}"), true))
                .isEqualTo("Approved (no field changes)");
    }

    @Test
    void nestedValuesAreShownAsJsonAndNonJsonPayloadsAsText() {
        assertThat(summary(entry(AdminAction.UPDATE, "{\"w\":{\"x\":1}}", "{\"w\":{\"x\":2}}"), true))
                .isEqualTo("w: {\"x\":1} → {\"x\":2}");
        assertThat(summary(entry(AdminAction.UPDATE, "not json", "{\"a\":1}"), true))
                .isEqualTo("not json → {\"a\":1}");
    }

    @Test
    void longSummariesAreCapped() {
        String big = "x".repeat(1000);
        String summary = summary(entry(AdminAction.CREATE, null, "{\"note\":\"" + big + "\"}"), true);

        assertThat(summary).hasSize(ChangeSummaries.MAX_LENGTH).endsWith("…");
    }
}
