package com.homefix.complaint.service;

/**
 * Metadata for a single evidence attachment submitted with a complaint (Requirement 16.1). Only the
 * file name and size are needed to enforce the count and per-file size limits; the binary content
 * is stored out of band (e.g. S3).
 */
public record AttachmentMetadata(String fileName, long sizeBytes) {
}
