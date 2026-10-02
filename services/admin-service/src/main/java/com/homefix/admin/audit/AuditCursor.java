package com.homefix.admin.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Keyset position in the Audit_Log listing: the {@code (loggedAt, id)} of the last entry on the
 * previous page. The next page holds the entries strictly after it in {@code loggedAt DESC, id DESC}
 * order, so pages stay stable while new entries are appended (an offset would shift by one for
 * every new entry and repeat rows).
 *
 * <p>Clients treat the token as opaque; it is base64url of {@code <ISO instant>|<uuid>}.
 */
public record AuditCursor(Instant loggedAt, UUID id) {

    public static AuditCursor after(AuditLogEntry entry) {
        return new AuditCursor(entry.getLoggedAt(), entry.getId());
    }

    public String encode() {
        String raw = loggedAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws InvalidAuditQueryException if the token was not produced by {@link #encode()}
     */
    public static AuditCursor decode(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            if (separator < 0) {
                throw new IllegalArgumentException("no separator");
            }
            return new AuditCursor(Instant.parse(raw.substring(0, separator)),
                    UUID.fromString(raw.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException malformed) {
            throw new InvalidAuditQueryException("cursor is not a valid audit log cursor");
        }
    }
}
