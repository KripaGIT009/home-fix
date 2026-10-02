package com.homefix.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The placeholder vocabulary: what each event supplies, what a stored template may use, and how
 * text is rendered.
 */
class TemplatePlaceholdersTest {

    @ParameterizedTest
    @EnumSource(NotificationEventType.class)
    void everyEventSuppliesTheBookingReference(NotificationEventType type) {
        assertThat(TemplatePlaceholders.allowedFor(type)).contains("bookingReference");
    }

    @Test
    void onlyComplaintStatusChangesSupplyTheComplaintStatus() {
        assertThat(TemplatePlaceholders.allowedFor(NotificationEventType.COMPLAINT_STATUS_CHANGED))
                .containsExactlyInAnyOrder("bookingReference", "complaintStatus");
        assertThat(TemplatePlaceholders.allowedFor(NotificationEventType.JOB_STARTED))
                .containsExactly("bookingReference");
    }

    @Test
    void everyBuiltInTemplateUsesOnlyItsEventsPlaceholders() {
        for (TemplateDefinition template : BuiltInTemplates.all()) {
            assertThat(TemplatePlaceholders.problems(template.eventType(), template.title())).as(template.key()).isEmpty();
            assertThat(TemplatePlaceholders.problems(template.eventType(), template.body())).as(template.key()).isEmpty();
        }
    }

    @Test
    void anUnknownPlaceholderIsAProblem() {
        assertThat(TemplatePlaceholders.problems(NotificationEventType.JOB_STARTED,
                "Hi {{customerName}}, {{bookingReference}} started"))
                .singleElement().asString().contains("{{customerName}}").contains("{{bookingReference}}");
        // complaintStatus exists, but a job event does not supply it.
        assertThat(TemplatePlaceholders.problems(NotificationEventType.JOB_STARTED, "{{complaintStatus}}"))
                .hasSize(1);
    }

    @Test
    void strayBracesAreAProblem() {
        assertThat(TemplatePlaceholders.problems(NotificationEventType.JOB_STARTED, "Done {{bookingReference"))
                .singleElement().asString().contains("malformed");
        assertThat(TemplatePlaceholders.problems(NotificationEventType.JOB_STARTED, "{{ booking reference }}"))
                .isNotEmpty();
    }

    @Test
    void renderFillsValuesInOnePassAndToleratesInnerWhitespace() {
        Map<String, String> values = TemplatePlaceholders.values(NotificationEventType.COMPLAINT_STATUS_CHANGED,
                Map.of("bookingReference", "{{complaintStatus}} $1", "complaintStatus", "REFUND_FAILED"));

        assertThat(TemplatePlaceholders.render("{{ bookingReference }} is {{complaintStatus}}.", values))
                .isEqualTo("{{complaintStatus}} $1 is refund failed.");
    }

    @Test
    void missingAttributesUseTheBuiltInFallbacks() {
        Map<String, String> values = TemplatePlaceholders.values(NotificationEventType.COMPLAINT_STATUS_CHANGED, Map.of());

        assertThat(values).containsEntry("bookingReference", "your booking").containsEntry("complaintStatus", "updated");
    }
}
