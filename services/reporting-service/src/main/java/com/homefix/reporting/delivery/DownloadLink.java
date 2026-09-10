package com.homefix.reporting.delivery;

import java.time.Instant;
import java.util.Objects;

/**
 * A time-limited link to a generated report artifact (Requirement 20.3). The download URL expires
 * 7 days after issue; {@link #isExpiredAt(Instant)} evaluates the boundary.
 */
public record DownloadLink(String url, Instant issuedAt, Instant expiresAt) {

    public DownloadLink {
        Objects.requireNonNull(url, "url is required");
        Objects.requireNonNull(issuedAt, "issuedAt is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
    }

    /** {@code true} once the supplied instant is at or after the expiry (Requirement 20.3). */
    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
