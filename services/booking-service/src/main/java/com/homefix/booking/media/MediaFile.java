package com.homefix.booking.media;

/**
 * Framework-agnostic view of an uploaded media file, so the storage port and validation are
 * testable without a servlet {@code MultipartFile}.
 */
public record MediaFile(String originalFilename, String contentType, long sizeBytes, byte[] bytes) {
}
