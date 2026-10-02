package com.homefix.rating.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.rating.api.dto.AdminReviewResponse;
import com.homefix.rating.api.dto.ModerateReviewRequest;
import com.homefix.rating.domain.ModerationStatus;
import com.homefix.rating.service.ReviewException;
import com.homefix.rating.service.ReviewService;

import jakarta.validation.Valid;

/**
 * Admin Portal review moderation (Requirement 19.2, Requirement 15.5, 15.9). The API gateway
 * routes {@code /admin/reviews/**} here unchanged; {@code RatingRbacConfig} restricts it to the
 * moderation tier (ADMIN, SUPER_ADMIN, SUPPORT_AGENT).
 *
 * <p>Decisions go through {@link ReviewService#moderate}, which reuses the approval and removal
 * paths, so the provider aggregate is recalculated and removals are audited with the acting staff
 * member (the JWT subject) exactly as on {@code /reviews/{id}/approval} and {@code /removal}.
 *
 * <p>Parameter names are spelled out because the build does not compile with {@code -parameters}.
 */
@RestController
@RequestMapping("/admin/reviews")
public class AdminReviewController {

    private final ReviewService reviewService;

    public AdminReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** Reviews newest first, optionally narrowed to one moderation status; bounded, not paged. */
    @GetMapping
    public List<AdminReviewResponse> list(
            @RequestParam(name = "status", required = false) ModerationStatus status) {
        return reviewService.listForAdmin(status).stream()
                .map(AdminReviewResponse::from)
                .toList();
    }

    /** Publishes or removes a review and returns it as it now stands. */
    @PostMapping("/{reviewId}/moderate")
    public AdminReviewResponse moderate(@PathVariable("reviewId") UUID reviewId,
                                        @Valid @RequestBody ModerateReviewRequest req) {
        return AdminReviewResponse.from(
                reviewService.moderate(reviewId, req.action(), currentUser(), req.reason()));
    }

    /** The acting staff member's user ID, from the principal set by {@code JwtValidationFilter}. */
    private UUID currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ReviewException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "no authenticated principal");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new ReviewException(HttpStatus.UNAUTHORIZED, "INVALID_PRINCIPAL",
                    "authenticated principal is not a valid user ID");
        }
    }
}
