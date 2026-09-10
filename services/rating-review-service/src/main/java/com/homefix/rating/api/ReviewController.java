package com.homefix.rating.api;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.rating.api.dto.RemoveReviewRequest;
import com.homefix.rating.api.dto.ReviewResponse;
import com.homefix.rating.api.dto.SubmitReviewRequest;
import com.homefix.rating.domain.Review;
import com.homefix.rating.service.AttachmentMetadata;
import com.homefix.rating.service.ReviewException;
import com.homefix.rating.service.ReviewService;
import com.homefix.rating.service.SubmitReviewCommand;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * REST surface for the Rating &amp; Review Service (Requirement 15).
 *
 * <p>Submission endpoints derive the authenticated reviewer from the JWT principal populated by the
 * shared {@code JwtValidationFilter} (Task 4). Admin moderation endpoints require an ADMIN principal
 * (enforced by the shared {@code RbacEnforcementFilter}) and record the acting Admin in the
 * Audit_Log (Requirement 15.9).
 */
@RestController
@RequestMapping("/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** Submit a customer→provider review within the open window (Requirement 15.2, 15.3). */
    @PostMapping
    public ResponseEntity<ReviewResponse> submit(@Valid @RequestBody SubmitReviewRequest req,
                                                 HttpServletRequest servletRequest) {
        Review review = reviewService.submitCustomerReview(toCommand(req, currentUser(), servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(ReviewResponse.from(review));
    }

    /** Submit a provider→customer review within the open window (Requirement 15.10). */
    @PostMapping("/customer")
    public ResponseEntity<ReviewResponse> submitProviderRating(@Valid @RequestBody SubmitReviewRequest req,
                                                               HttpServletRequest servletRequest) {
        Review review = reviewService.submitProviderReview(toCommand(req, currentUser(), servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(ReviewResponse.from(review));
    }

    /** Admin: approve a flagged review so it re-enters the aggregate (Requirement 15.5). */
    @PostMapping("/{reviewId}/approval")
    public ReviewResponse approve(@PathVariable UUID reviewId) {
        return ReviewResponse.from(reviewService.approveReview(reviewId));
    }

    /** Admin: remove a review for a policy violation; recalculates aggregate + audits (15.9). */
    @PostMapping("/{reviewId}/removal")
    public ResponseEntity<Void> remove(@PathVariable UUID reviewId,
                                       @Valid @RequestBody RemoveReviewRequest req) {
        reviewService.removeReview(reviewId, currentUser(), req.reason());
        return ResponseEntity.noContent().build();
    }

    private SubmitReviewCommand toCommand(SubmitReviewRequest req, UUID reviewerId,
                                          HttpServletRequest servletRequest) {
        List<AttachmentMetadata> attachments = req.attachments() == null ? List.of()
                : req.attachments().stream()
                        .map(a -> new AttachmentMetadata(a.fileName(), a.sizeBytes()))
                        .collect(Collectors.toList());
        return new SubmitReviewCommand(req.bookingId(), reviewerId, req.overall(), req.behavior(),
                req.quality(), req.timeliness(), req.pricingTransparency(), req.reviewText(),
                attachments, clientIp(servletRequest));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // First hop is the originating client.
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * @return the authenticated user's ID, taken from the principal name populated by the shared
     *         {@code JwtValidationFilter} (Task 4).
     */
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
