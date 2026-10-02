package com.homefix.provider.domain;

import java.util.UUID;

/** One skill tag of one provider, projected for batch reads (Requirement 19.2). */
public record ProviderSkillTag(UUID providerId, String tag) {
}
