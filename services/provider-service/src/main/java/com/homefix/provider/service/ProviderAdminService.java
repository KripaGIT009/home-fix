package com.homefix.provider.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.homefix.provider.domain.ProviderAdminRow;
import com.homefix.provider.domain.ProviderProfileRepository;
import com.homefix.provider.domain.ProviderSkillTag;
import com.homefix.provider.verification.VerificationAdminClientPort;

/**
 * The Admin Portal's provider management (Requirement 19.2), plus the batch name lookup the
 * Verification Service's review queue borrows (Requirement 19.3).
 *
 * <p>The Provider Service owns the profile (display name, skills, rating); the provider's
 * verification status — including suspension — is owned by the Verification Service, so the list
 * asks it once per page and status changes are delegated to its suspend / reinstate transitions.
 * There is no provider-side account status: an Admin "suspends" a provider by suspending their
 * verification, which is what removes them from dispatch (Requirement 5.9).
 *
 * <p>Deliberately not {@code @Transactional}: each read is a single projection statement in its own
 * short transaction, and holding a database transaction open across the HTTP call to the
 * Verification Service would pin a connection for no benefit.
 */
@Service
public class ProviderAdminService {

    /** Server-side cap on the Admin list: the portal's table takes a bare array with no paging. */
    public static final int ADMIN_LIST_LIMIT = 200;

    /** Cap on ids per summaries request; matches the Verification Service's queue cap. */
    public static final int MAX_SUMMARY_IDS = 200;

    /** The account statuses the Admin Portal can request (its {@code AdminProvider['status']}). */
    public enum RequestedStatus {
        ACTIVE, SUSPENDED, DEACTIVATED
    }

    private final ProviderProfileRepository repository;
    private final VerificationAdminClientPort verification;

    public ProviderAdminService(ProviderProfileRepository repository,
                                VerificationAdminClientPort verification) {
        this.repository = repository;
        this.verification = verification;
    }

    /**
     * Providers for the Admin list, newest first, at most {@value #ADMIN_LIST_LIMIT}, optionally
     * filtered by a case-insensitive substring of the display name. Verification statuses come
     * from one batch call; when it fails the list is still returned with
     * {@link AdminProviderView#verificationKnown()} {@code false}.
     */
    public List<AdminProviderView> list(String search) {
        PageRequest page = PageRequest.of(0, ADMIN_LIST_LIMIT);
        List<ProviderAdminRow> rows = search == null || search.isBlank()
                ? repository.findAdminRows(page)
                : repository.searchAdminRows(likePattern(search), page);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(ProviderAdminRow::id).toList();
        Map<UUID, String> skills = primarySkills(ids);
        Optional<Map<UUID, String>> statuses = verification.statusesOf(ids);
        return rows.stream()
                .map(row -> view(row, skills.get(row.id()), statuses))
                .toList();
    }

    /**
     * Applies an Admin's requested account status (Requirement 19.2) by driving the Verification
     * Service: {@code SUSPENDED} suspends an approved provider (Requirement 5.9), {@code ACTIVE}
     * reinstates a suspended one (Requirement 5.1). {@code DEACTIVATED} has no backing transition
     * anywhere in the platform, so it is refused rather than pretended.
     *
     * @param actorId the acting Admin, recorded in the verification audit trail (Requirement 5.11)
     * @throws ProviderException 400 {@code VALIDATION_ERROR} for an unknown status, 400
     *         {@code UNSUPPORTED_PROVIDER_STATUS} for {@code DEACTIVATED}, 404
     *         {@code PROVIDER_NOT_FOUND}, and whatever the Verification Service refuses with
     *         (e.g. 409 {@code INVALID_STATE_TRANSITION})
     */
    public AdminProviderView changeStatus(UUID providerId, String requestedStatus, UUID actorId) {
        RequestedStatus requested = parse(requestedStatus);
        List<ProviderAdminRow> rows = repository.findAdminRowsByIds(List.of(providerId));
        if (rows.isEmpty()) {
            throw ProviderException.notFound("Provider " + providerId + " not found");
        }
        String verificationStatus = switch (requested) {
            case SUSPENDED -> verification.suspend(providerId, actorId, "Suspended from the Admin Portal");
            case ACTIVE -> verification.reinstate(providerId, actorId, "Reinstated from the Admin Portal");
            case DEACTIVATED -> throw new ProviderException(HttpStatus.BAD_REQUEST,
                    "UNSUPPORTED_PROVIDER_STATUS",
                    "DEACTIVATED is not supported: providers can only be suspended (SUSPENDED) or "
                            + "reinstated (ACTIVE)");
        };
        Map<UUID, String> skills = primarySkills(List.of(providerId));
        return view(rows.get(0), skills.get(providerId),
                Optional.of(Map.of(providerId, verificationStatus)));
    }

    /**
     * Display name and primary skill for each of {@code ids} that has a profile — the batch lookup
     * behind the Verification Service's review queue. Unknown ids are absent.
     *
     * @throws ProviderException 400 {@code VALIDATION_ERROR} for more than {@value #MAX_SUMMARY_IDS} ids
     */
    public List<ProviderSummaryView> summaries(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > MAX_SUMMARY_IDS) {
            throw ProviderException.validation("at most " + MAX_SUMMARY_IDS + " ids per request");
        }
        List<UUID> distinct = ids.stream().distinct().toList();
        Map<UUID, String> skills = primarySkills(distinct);
        return repository.findAdminRowsByIds(distinct).stream()
                .map(row -> new ProviderSummaryView(row.id(), row.displayName(), skills.get(row.id())))
                .toList();
    }

    // ----------------------------------------------------------------------------------------

    private static AdminProviderView view(ProviderAdminRow row, String primarySkill,
                                          Optional<Map<UUID, String>> statuses) {
        return new AdminProviderView(row, primarySkill, statuses.isPresent(),
                statuses.map(m -> m.get(row.id())).orElse(null));
    }

    /**
     * The first skill tag of each provider. Tags are an unordered collection, so "first" is the
     * order the database returns them in — the same tag {@code getSkillTags().get(0)} would give.
     */
    private Map<UUID, String> primarySkills(List<UUID> ids) {
        Map<UUID, String> skills = new LinkedHashMap<>();
        for (ProviderSkillTag tag : repository.findSkillTags(ids)) {
            skills.putIfAbsent(tag.providerId(), tag.tag());
        }
        return skills;
    }

    private static RequestedStatus parse(String status) {
        if (status == null || status.isBlank()) {
            throw ProviderException.validation("status is required");
        }
        try {
            return RequestedStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ProviderException.validation(
                    "status must be one of ACTIVE, SUSPENDED, DEACTIVATED");
        }
    }

    /** A lower-cased {@code %term%} pattern with LIKE metacharacters escaped by {@code \}. */
    static String likePattern(String search) {
        String escaped = search.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /**
     * One provider of the Admin list: the provider-owned columns plus the raw verification status.
     *
     * @param verificationKnown  {@code false} when the Verification Service could not be asked
     * @param verificationStatus the raw {@code VerificationStatus} name, or {@code null} when the
     *                           provider has no verification record (or it is unknown)
     */
    public record AdminProviderView(ProviderAdminRow row, String primarySkill,
                                    boolean verificationKnown, String verificationStatus) {
    }

    /** What the Verification Service's review queue shows about a provider. */
    public record ProviderSummaryView(UUID id, String displayName, String primarySkill) {
    }
}
