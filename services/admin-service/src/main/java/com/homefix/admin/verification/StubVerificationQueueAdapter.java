package com.homefix.admin.verification;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Placeholder {@link VerificationQueuePort} returning an empty queue. The HTTP adapter over the
 * Verification Service is wired in a later integration task; this keeps the Admin Service
 * independently buildable.
 */
@Component
public class StubVerificationQueueAdapter implements VerificationQueuePort {

    @Override
    public List<VerificationQueueEntry> findDocumentSubmitted() {
        return List.of();
    }
}
