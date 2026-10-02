package com.homefix.provider.api.dto;

import java.util.List;
import java.util.UUID;

import com.homefix.provider.service.ProviderAdminService.ProviderSummaryView;

/**
 * Response of {@code GET /internal/providers/summaries}: display name and primary skill of each
 * requested provider that has a profile, for the Verification Service's Admin review queue
 * (Requirement 19.3). Unknown ids are simply absent.
 */
public record ProviderSummariesResponse(List<Summary> providers) {

    /** One provider; either text field may be {@code null}. */
    public record Summary(UUID id, String displayName, String primarySkill) {
    }

    public static ProviderSummariesResponse from(List<ProviderSummaryView> views) {
        return new ProviderSummariesResponse(views.stream()
                .map(v -> new Summary(v.id(), v.displayName(), v.primarySkill()))
                .toList());
    }
}
