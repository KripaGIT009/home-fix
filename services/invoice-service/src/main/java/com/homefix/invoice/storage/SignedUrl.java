package com.homefix.invoice.storage;

import java.time.Instant;

/**
 * A time-limited download URL for a stored invoice PDF (Requirement 13.3). The {@link #expiresAt}
 * makes the 72-hour validity assertable by callers and tests.
 */
public record SignedUrl(String url, Instant expiresAt) {
}
