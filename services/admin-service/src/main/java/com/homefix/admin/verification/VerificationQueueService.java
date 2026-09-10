package com.homefix.admin.verification;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

/**
 * Builds the Admin Verification Queue view (Requirement 19.3): all providers in
 * DOCUMENT_SUBMITTED status sorted by submission date oldest first, each with its submitted
 * documents available for inline viewing.
 */
@Service
public class VerificationQueueService {

    private final VerificationQueuePort port;

    public VerificationQueueService(VerificationQueuePort port) {
        this.port = port;
    }

    /** Returns the queue sorted by {@code submittedAt} ascending (oldest first). */
    public List<VerificationQueueEntry> queue() {
        return port.findDocumentSubmitted().stream()
                .sorted(Comparator.comparing(VerificationQueueEntry::submittedAt))
                .toList();
    }
}
