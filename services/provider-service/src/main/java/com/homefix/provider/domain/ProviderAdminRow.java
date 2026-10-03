package com.homefix.provider.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The provider-owned columns the Admin provider list shows (Requirement 19.2), read as a
 * projection of the profile row alone. The profile's skill tags, category selections and
 * availability slots are all EAGER collections, so listing whole aggregates would cost several
 * extra statements per provider; this keeps the list to one statement plus one for the tags.
 *
 * <p>{@code bankAccountEncrypted} is the stored ciphertext, carried only so the service can derive
 * the masked label; it is never part of a response.
 */
public record ProviderAdminRow(UUID id, String displayName, BigDecimal aggregateRating,
                               String bankAccountEncrypted, boolean bankAccountVerified) {

    /** A row without a bank account on file. */
    public ProviderAdminRow(UUID id, String displayName, BigDecimal aggregateRating) {
        this(id, displayName, aggregateRating, null, false);
    }

    @Override
    public String toString() {
        return "ProviderAdminRow[id=" + id + ", displayName=" + displayName
                + ", aggregateRating=" + aggregateRating
                + ", bankAccount=" + (bankAccountEncrypted == null ? "none" : "on file")
                + ", bankAccountVerified=" + bankAccountVerified + "]";
    }
}
