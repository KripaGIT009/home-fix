package com.homefix.admin.verification;

import java.util.List;

/**
 * Port over the Verification Service for the Admin Verification Queue (Requirement 19.3). A
 * production adapter calls the Verification Service API; tests supply a fake. The Admin Service
 * does not read the Verification Service's tables directly (per-service DB isolation).
 */
public interface VerificationQueuePort {

    /** Returns all providers currently in DOCUMENT_SUBMITTED status (unordered). */
    List<VerificationQueueEntry> findDocumentSubmitted();
}
