package com.homefix.rating.service;

/**
 * Metadata for a single review photo attachment (Requirement 15.3). The bytes themselves live in
 * object storage; only the descriptor is validated and referenced here.
 *
 * @param fileName  original file name (informational)
 * @param sizeBytes size in bytes, validated against the per-file maximum
 */
public record AttachmentMetadata(String fileName, long sizeBytes) {
}
