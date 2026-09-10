package com.homefix.admin.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Unit test for the Verification Queue ordering (Requirement 19.3): entries are returned sorted by
 * submission date oldest first, each carrying its documents for inline viewing.
 */
class VerificationQueueServiceTest {

    @Test
    void queueIsSortedBySubmissionDateOldestFirst() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        var newest = new VerificationQueueEntry(p1, Instant.parse("2026-03-03T00:00:00Z"),
                List.of(new SubmittedDocument("GOVERNMENT_ID", "https://view/1", "image/jpeg")));
        var oldest = new VerificationQueueEntry(p2, Instant.parse("2026-01-01T00:00:00Z"), List.of());
        var middle = new VerificationQueueEntry(p3, Instant.parse("2026-02-02T00:00:00Z"), List.of());

        VerificationQueuePort port = () -> List.of(newest, oldest, middle);
        VerificationQueueService service = new VerificationQueueService(port);

        assertThat(service.queue())
                .extracting(VerificationQueueEntry::providerId)
                .containsExactly(p2, p3, p1);
    }

    @Test
    void entriesExposeDocumentsForInlineViewing() {
        UUID provider = UUID.randomUUID();
        var doc = new SubmittedDocument("SKILL_CERT", "https://view/abc", "application/pdf");
        VerificationQueuePort port = () -> List.of(
                new VerificationQueueEntry(provider, Instant.parse("2026-01-01T00:00:00Z"), List.of(doc)));

        List<VerificationQueueEntry> queue = new VerificationQueueService(port).queue();

        assertThat(queue.get(0).documents()).containsExactly(doc);
        assertThat(queue.get(0).documents().get(0).inlineViewUrl()).isEqualTo("https://view/abc");
    }
}
