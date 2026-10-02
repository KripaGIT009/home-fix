package com.homefix.provider.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The provider-owned columns the Admin provider list shows (Requirement 19.2), read as a
 * projection of the profile row alone. The profile's skill tags, category selections and
 * availability slots are all EAGER collections, so listing whole aggregates would cost several
 * extra statements per provider; this keeps the list to one statement plus one for the tags.
 */
public record ProviderAdminRow(UUID id, String displayName, BigDecimal aggregateRating) {
}
