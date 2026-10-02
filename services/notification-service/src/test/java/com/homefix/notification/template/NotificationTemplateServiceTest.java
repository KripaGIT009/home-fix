package com.homefix.notification.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.notification.domain.BuiltInTemplates;
import com.homefix.notification.domain.NotificationChannel;
import com.homefix.notification.domain.TemplateText;

/**
 * Template management (Requirement 19.2): listing merges stored text over the built-in catalogue,
 * edits are validated against the placeholders the event supplies, and the render cache serves
 * an edit immediately on this instance and re-reads the table after its TTL.
 */
class NotificationTemplateServiceTest {

    private static final String PUSH_ID = "JOB_STARTED.CUSTOMER.PUSH";
    private static final String SMS_ID = "PROVIDER_ARRIVED.CUSTOMER.SMS";
    private static final UUID ADMIN = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final Map<String, NotificationTemplateEntity> table = new LinkedHashMap<>();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T10:00:00Z"));
    private NotificationTemplateRepository repository;
    private NotificationTemplateService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationTemplateRepository.class);
        when(repository.findAll()).thenAnswer(invocation -> new ArrayList<>(table.values()));
        when(repository.findById(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(table.get(invocation.<String>getArgument(0))));
        when(repository.save(any(NotificationTemplateEntity.class))).thenAnswer(invocation -> {
            NotificationTemplateEntity row = invocation.getArgument(0);
            table.put(row.getId(), row);
            return row;
        });
        service = new NotificationTemplateService(repository, clock);
    }

    private void store(String id, String subject, String body) {
        var template = BuiltInTemplates.findById(id).orElseThrow();
        table.put(id, new NotificationTemplateEntity(id, template.definition().key(),
                template.definition().nameFor(template.channel()), template.channel(), subject, body,
                clock.instant(), null));
    }

    @Test
    void listCoversEveryBuiltInTemplateAndChannelWithStoredTextWhereThereIsSome() {
        store(PUSH_ID, "Started!", "Underway: {{bookingReference}}");

        List<NotificationTemplateView> templates = service.list();

        assertThat(templates).hasSize(BuiltInTemplates.allChannelTemplates().size());
        assertThat(templates).filteredOn(t -> t.id().equals(PUSH_ID)).singleElement()
                .satisfies(t -> {
                    assertThat(t.key()).isEqualTo("JOB_STARTED.CUSTOMER");
                    assertThat(t.channel()).isEqualTo(NotificationChannel.PUSH);
                    assertThat(t.name()).isEqualTo("Job started (customer) — Push");
                    assertThat(t.subject()).isEqualTo("Started!");
                    assertThat(t.body()).isEqualTo("Underway: {{bookingReference}}");
                });
        // No row: the built-in text, and SMS has no subject.
        assertThat(templates).filteredOn(t -> t.id().equals(SMS_ID)).singleElement()
                .satisfies(t -> {
                    assertThat(t.subject()).isNull();
                    assertThat(t.body()).isEqualTo("Your professional for {{bookingReference}} has arrived.");
                });
    }

    @Test
    void updateReplacesTheTextAndStampsTheEditor() {
        store(PUSH_ID, "Job started", "Work on {{bookingReference}} has started.");

        NotificationTemplateView updated = service.update(PUSH_ID, "We're on it", "{{bookingReference}} is underway", ADMIN);

        assertThat(updated.subject()).isEqualTo("We're on it");
        assertThat(updated.body()).isEqualTo("{{bookingReference}} is underway");
        assertThat(table.get(PUSH_ID).getUpdatedBy()).isEqualTo(ADMIN);
        assertThat(table.get(PUSH_ID).getUpdatedAt()).isEqualTo(clock.instant());
    }

    @Test
    void anOmittedSubjectKeepsTheCurrentOne() {
        store(PUSH_ID, "Custom heading", "Work on {{bookingReference}} has started.");

        NotificationTemplateView updated = service.update(PUSH_ID, null, "New body", ADMIN);

        assertThat(updated.subject()).isEqualTo("Custom heading");
        assertThat(updated.body()).isEqualTo("New body");
    }

    @Test
    void aMissingRowIsRecreatedFromTheCatalogue() {
        NotificationTemplateView updated = service.update(SMS_ID, null, "Arrived for {{bookingReference}}", ADMIN);

        assertThat(updated.subject()).isNull();
        assertThat(table.get(SMS_ID).getTemplateKey()).isEqualTo("PROVIDER_ARRIVED.CUSTOMER");
        assertThat(table.get(SMS_ID).getChannel()).isEqualTo(NotificationChannel.SMS);
    }

    @Test
    void aPlaceholderTheEventDoesNotSupplyIsRejectedAndNothingIsSaved() {
        assertThatThrownBy(() -> service.update(PUSH_ID, "Hi {{customerName}}", "Now {{complaintStatus}}", ADMIN))
                .isInstanceOf(InvalidTemplateException.class)
                .satisfies(ex -> assertThat(((InvalidTemplateException) ex).getProblems())
                        .hasSize(2)
                        .anySatisfy(p -> assertThat(p).startsWith("body:").contains("{{complaintStatus}}"))
                        .anySatisfy(p -> assertThat(p).startsWith("subject:").contains("{{customerName}}")));
        verify(repository, never()).save(any());
    }

    @Test
    void theComplaintStatusEventMayUseItsStatusPlaceholder() {
        NotificationTemplateView updated = service.update("COMPLAINT_STATUS_CHANGED.CUSTOMER.IN_APP", null,
                "Complaint on {{bookingReference}}: {{complaintStatus}}", ADMIN);

        assertThat(updated.body()).contains("{{complaintStatus}}");
    }

    @Test
    void smsTakesNoSubjectAndBlankTextIsRejected() {
        assertThatThrownBy(() -> service.update(SMS_ID, "Heading", "Body", ADMIN))
                .isInstanceOf(InvalidTemplateException.class)
                .hasMessageContaining("SMS templates have no subject");
        assertThatThrownBy(() -> service.update(PUSH_ID, " ", " ", ADMIN))
                .isInstanceOf(InvalidTemplateException.class)
                .satisfies(ex -> assertThat(((InvalidTemplateException) ex).getProblems()).hasSize(2));
        assertThatThrownBy(() -> service.update(PUSH_ID, null, "x".repeat(1001), ADMIN))
                .isInstanceOf(InvalidTemplateException.class)
                .hasMessageContaining("at most 1000");
    }

    @Test
    void anUnknownIdIsNotFound() {
        assertThatThrownBy(() -> service.update("JOB_STARTED.PROVIDER.PUSH", null, "x", ADMIN))
                .isInstanceOf(TemplateNotFoundException.class);
        // A real template on a channel it is not sent on is not a template either.
        assertThatThrownBy(() -> service.update("JOB_STARTED.CUSTOMER.SMS", null, "x", ADMIN))
                .isInstanceOf(TemplateNotFoundException.class);
    }

    @Test
    void findServesTheCachedSnapshotUntilAnEditEvictsIt() {
        store(PUSH_ID, "Job started", "Original");

        assertThat(service.find(PUSH_ID)).contains(new TemplateText("Job started", "Original"));
        assertThat(service.find(SMS_ID)).isEmpty();
        verify(repository, times(1)).findAll();

        service.update(PUSH_ID, null, "Edited", ADMIN);

        assertThat(service.find(PUSH_ID)).map(TemplateText::body).contains("Edited");
    }

    @Test
    void findReReadsTheTableOnceTheSnapshotExpires() {
        store(PUSH_ID, "Job started", "Original");
        service.find(PUSH_ID);

        // Another replica edits the row: this instance keeps its snapshot until the TTL passes.
        table.put(PUSH_ID, new NotificationTemplateEntity(PUSH_ID, "JOB_STARTED.CUSTOMER", "n",
                NotificationChannel.PUSH, "Job started", "Edited elsewhere", clock.instant(), ADMIN));
        clock.advance(NotificationTemplateService.CACHE_TTL.minusSeconds(1));
        assertThat(service.find(PUSH_ID)).map(TemplateText::body).contains("Original");

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.find(PUSH_ID)).map(TemplateText::body).contains("Edited elsewhere");
    }

    /** A clock tests can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
