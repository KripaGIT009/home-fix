package com.homefix.verification.service;

import com.homefix.verification.domain.DocumentType;

/**
 * A single document to upload during the {@code POST /verifications/{providerId}/documents}
 * flow (Requirement 5.3).
 */
public record DocumentUpload(DocumentType type, byte[] content, String contentType) {
}
