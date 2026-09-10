package com.homefix.rating.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.rating.account.ReviewerAccountPort;
import com.homefix.rating.alert.AdminAlertPort;
import com.homefix.rating.audit.AuditLogEntry;
import com.homefix.rating.audit.AuditLogRepository;
import com.homefix.rating.config.RatingProperties;
import com.homefix.rating.domain.AggregateCalculator;
import com.homefix.rating.domain.Review;
import com.homefix.rating.domain.ReviewPrompt;
import com.homefix.rating.domain.ReviewPromptRepository;
import com.homefix.rating.domain.ReviewRepository;
import com.homefix.rating.domain.ReviewerRole;
import com.homefix.rating.event.PaymentCompletedEvent;
import com.homefix.rating.event.ReviewSubmittedPublisher;
import com.homefix.rating.provider.ProviderRatingPort;

/**
 * Orchestrates the rating-and-review lifecycle (Requirement 15):
 *
 * <ol>
 *   <li>On {@code PaymentCompleted}, opens 7-day review prompts for both the customer and the
 *       provider (15.1, 15.10), idempotent per payment.</li>
 *   <li>On submission, enforces the open window (15.2, Property 15), validates ratings/text/
 *       attachments (15.3), runs fraud detection (15.5, Property 17), persists the review, and —
 *       for a non-flagged customer review — recomputes the provider's weighted aggregate (15.4,
 *       Property 16) and publishes {@code ReviewSubmitted} (15.6).</li>
 *   <li>When the recomputed aggregate drops below threshold, flags the provider UNDER_REVIEW and
 *       alerts Admin (15.7, 15.8).</li>
 *   <li>On Admin removal, deactivates the review, recomputes the aggregate, and writes an
 *       Audit_Log entry with the actor and reason (15.9).</li>
 * </ol>
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewRepository reviewRepository;
    private final ReviewPromptRepository promptRepository;
    private final AuditLogRepository auditLogRepository;
    private final ReviewSubmittedPublisher reviewSubmittedPublisher;
    private final ProviderRatingPort providerRatingPort;
    private final AdminAlertPort adminAlertPort;
    private final ReviewerAccountPort reviewerAccountPort;
    private final RatingProperties properties;
    private final AggregateCalculator aggregateCalculator;
    private final FraudDetector fraudDetector;
    private final Clock clock;

    // These classes keep a second, package-private constructor so tests can pin the Clock.
    // With more than one constructor Spring will not guess: without @Autowired it falls back
    // to a no-arg constructor that does not exist and the bean fails to instantiate.
    @Autowired
    public ReviewService(ReviewRepository reviewRepository,
                         ReviewPromptRepository promptRepository,
                         AuditLogRepository auditLogRepository,
                         ReviewSubmittedPublisher reviewSubmittedPublisher,
                         ProviderRatingPort providerRatingPort,
                         AdminAlertPort adminAlertPort,
                         ReviewerAccountPort reviewerAccountPort,
                         RatingProperties properties) {
        this(reviewRepository, promptRepository, auditLogRepository, reviewSubmittedPublisher,
                providerRatingPort, adminAlertPort, reviewerAccountPort, properties, Clock.systemUTC());
    }

    // Visible for testing so unit tests can pin the clock.
    ReviewService(ReviewRepository reviewRepository,
                  ReviewPromptRepository promptRepository,
                  AuditLogRepository auditLogRepository,
                  ReviewSubmittedPublisher reviewSubmittedPublisher,
                  ProviderRatingPort providerRatingPort,
                  AdminAlertPort adminAlertPort,
                  ReviewerAccountPort reviewerAccountPort,
                  RatingProperties properties,
                  Clock clock) {
        this.reviewRepository = reviewRepository;
        this.promptRepository = promptRepository;
        this.auditLogRepository = auditLogRepository;
        this.reviewSubmittedPublisher = reviewSubmittedPublisher;
        this.providerRatingPort = providerRatingPort;
        this.adminAlertPort = adminAlertPort;
        this.reviewerAccountPort = reviewerAccountPort;
        this.properties = properties;
        this.aggregateCalculator = new AggregateCalculator(
                properties.getRecentWindow(), properties.getRecentWeight(), properties.getOlderWeight());
        this.fraudDetector = new FraudDetector(properties.getFraud());
        this.clock = clock;
    }

    // ---- PaymentCompleted -> open prompts (Requirement 15.1, 15.10) ----------------------------

    /**
     * Opens the customer→provider and provider→customer review prompts for a completed payment.
     * Idempotent per payment so a redelivered event does not open a second pair of prompts.
     */
    @Transactional
    public void openReviewPrompts(PaymentCompletedEvent event) {
        if (promptRepository.existsByPaymentId(event.paymentId())) {
            log.debug("Review prompts already open for payment {}; skipping", event.paymentId());
            return;
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.getReviewWindow());

        promptRepository.save(ReviewPrompt.open(event.bookingId(), event.paymentId(),
                ReviewerRole.CUSTOMER, event.customerId(), event.providerId(), now, expiresAt));
        promptRepository.save(ReviewPrompt.open(event.bookingId(), event.paymentId(),
                ReviewerRole.PROVIDER, event.providerId(), event.customerId(), now, expiresAt));
        log.debug("Opened review prompts for booking {} expiring {}", event.bookingId(), expiresAt);
    }

    // ---- Submission (Requirement 15.2, 15.3, 15.4, 15.5, 15.6) ---------------------------------

    /**
     * Submits a customer→provider review. Enforces the review window, validates the payload, runs
     * fraud detection, persists the review, and — when not flagged — recomputes the provider
     * aggregate and publishes {@code ReviewSubmitted}.
     *
     * @return the persisted review (flagged or not)
     */
    @Transactional
    public Review submitCustomerReview(SubmitReviewCommand command) {
        ReviewPrompt prompt = requireOpenPrompt(command.bookingId(), ReviewerRole.CUSTOMER);
        guardNotAlreadyReviewed(command.bookingId(), prompt.getReviewerId());
        validatePayload(command, true);

        Instant now = clock.instant();
        UUID providerId = prompt.getRevieweeId();

        Set<FraudDetector.Trigger> triggers = detectFraud(command, providerId, now);
        boolean flagged = !triggers.isEmpty();

        Review review = Review.customerReview(command.bookingId(), prompt.getReviewerId(), providerId,
                command.overall(), command.behavior(), command.quality(), command.timeliness(),
                command.pricingTransparency(), command.reviewText(), command.sourceIp(), flagged, now);
        reviewRepository.save(review);
        prompt.markFulfilled();
        promptRepository.save(prompt);

        if (flagged) {
            log.warn("Review {} flagged for moderation: {}", review.getId(),
                    FraudDetector.describe(triggers));
            // Flagged reviews are excluded from the aggregate and do not emit ReviewSubmitted
            // until an Admin approves them (Requirement 15.5, 15.6, Property 17).
            return review;
        }

        recalculateProviderAggregate(providerId, now);
        reviewSubmittedPublisher.publish(review);
        return review;
    }

    /**
     * Submits a provider→customer review (single overall dimension, Requirement 15.10). These do
     * not affect a provider's aggregate. Fraud detection is not applied to provider ratings of
     * customers, but the window and payload are enforced.
     */
    @Transactional
    public Review submitProviderReview(SubmitReviewCommand command) {
        ReviewPrompt prompt = requireOpenPrompt(command.bookingId(), ReviewerRole.PROVIDER);
        guardNotAlreadyReviewed(command.bookingId(), prompt.getReviewerId());
        validatePayload(command, false);

        Instant now = clock.instant();
        Review review = Review.providerReview(command.bookingId(), prompt.getReviewerId(),
                prompt.getRevieweeId(), command.overall(), command.reviewText(), command.sourceIp(),
                false, now);
        reviewRepository.save(review);
        prompt.markFulfilled();
        promptRepository.save(prompt);
        reviewSubmittedPublisher.publish(review);
        return review;
    }

    // ---- Admin moderation (Requirement 15.5, 15.9) ---------------------------------------------

    /**
     * Admin-approves a flagged review, clearing the flag so it re-enters the aggregate, then
     * recomputes the provider's aggregate and publishes the previously-withheld
     * {@code ReviewSubmitted} event (Requirement 15.5, 15.6).
     */
    @Transactional
    public Review approveReview(UUID reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> ReviewException.notFound("review not found: " + reviewId));
        if (!review.isFlagged()) {
            return review;
        }
        review.approve();
        reviewRepository.save(review);

        if (review.getReviewerRole() == ReviewerRole.CUSTOMER) {
            recalculateProviderAggregate(review.getRevieweeId(), clock.instant());
        }
        reviewSubmittedPublisher.publish(review);
        return review;
    }

    /**
     * Admin-removes a review for a policy violation: deactivates it, recomputes the provider
     * aggregate, and writes an Audit_Log entry capturing the acting Admin and the reason
     * (Requirement 15.9).
     */
    @Transactional
    public void removeReview(UUID reviewId, UUID adminId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ReviewException.validation("removal reason is required");
        }
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> ReviewException.notFound("review not found: " + reviewId));

        Instant now = clock.instant();
        review.deactivate();
        reviewRepository.save(review);

        auditLogRepository.save(AuditLogEntry.reviewRemoval(reviewId, adminId, reason, now));

        if (review.getReviewerRole() == ReviewerRole.CUSTOMER) {
            recalculateProviderAggregate(review.getRevieweeId(), now);
        }
        log.info("Admin {} removed review {} (reason recorded in audit log)", adminId, reviewId);
    }

    // ---- Aggregate recalculation (Requirement 15.4, 15.7, 15.8, Property 16, 17) ---------------

    /** Recomputes the provider aggregate over contributing reviews and applies threshold effects. */
    BigDecimal recalculateProviderAggregate(UUID providerId, Instant now) {
        List<Review> reviews = reviewRepository.findByRevieweeIdAndReviewerRole(
                providerId, ReviewerRole.CUSTOMER);
        BigDecimal aggregate = aggregateCalculator.aggregate(reviews, now);
        providerRatingPort.updateAggregateRating(providerId, aggregate);

        if (aggregate != null && aggregate.compareTo(properties.getUnderReviewThreshold()) < 0) {
            boolean newlyFlagged = providerRatingPort.flagUnderReview(providerId);
            if (newlyFlagged) {
                adminAlertPort.providerBelowThreshold(providerId, aggregate,
                        properties.getUnderReviewThreshold());
            }
        }
        return aggregate;
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private ReviewPrompt requireOpenPrompt(UUID bookingId, ReviewerRole role) {
        ReviewPrompt prompt = promptRepository.findByBookingIdAndReviewerRole(bookingId, role)
                .orElseThrow(() -> ReviewException.notFound(
                        "no review prompt for booking " + bookingId + " and role " + role));
        if (!prompt.isOpenAt(clock.instant())) {
            throw ReviewException.windowClosed(
                    "the review period for booking " + bookingId + " has closed");
        }
        return prompt;
    }

    private void guardNotAlreadyReviewed(UUID bookingId, UUID reviewerId) {
        if (reviewRepository.existsByBookingIdAndReviewerId(bookingId, reviewerId)) {
            throw ReviewException.duplicate("a review already exists for this booking");
        }
    }

    private void validatePayload(SubmitReviewCommand command, boolean allDimensions) {
        List<String> errors = new ArrayList<>();
        validateStar("overall", command.overall(), errors);
        if (allDimensions) {
            validateStar("behavior", command.behavior(), errors);
            validateStar("quality", command.quality(), errors);
            validateStar("timeliness", command.timeliness(), errors);
            validateStar("pricingTransparency", command.pricingTransparency(), errors);
        }
        if (command.reviewText() != null
                && command.reviewText().length() > properties.getMaxReviewTextLength()) {
            errors.add("reviewText exceeds " + properties.getMaxReviewTextLength() + " characters");
        }
        validateAttachments(command.attachments(), errors);

        if (!errors.isEmpty()) {
            throw new ReviewException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR", "review submission is invalid", errors);
        }
    }

    private void validateStar(String field, int value, List<String> errors) {
        if (value < 1 || value > 5) {
            errors.add(field + " must be an integer 1-5");
        }
    }

    private void validateAttachments(List<AttachmentMetadata> attachments, List<String> errors) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        int maxCount = properties.getAttachments().getMaxCount();
        long maxBytes = properties.getAttachments().getMaxBytes();
        if (attachments.size() > maxCount) {
            errors.add("at most " + maxCount + " photo attachments are allowed");
        }
        for (AttachmentMetadata attachment : attachments) {
            if (attachment.sizeBytes() > maxBytes) {
                errors.add("attachment " + attachment.fileName() + " exceeds the "
                        + maxBytes + "-byte limit");
            }
        }
    }

    private Set<FraudDetector.Trigger> detectFraud(SubmitReviewCommand command, UUID providerId,
                                                   Instant now) {
        int sameIpCount = 0;
        if (command.sourceIp() != null) {
            Instant since = now.minus(properties.getFraud().getSameIpWindow());
            List<Review> recent = reviewRepository.findBySourceIpAndSubmittedAtGreaterThanEqual(
                    command.sourceIp(), since);
            // The submission about to be written plus any prior reviews from this IP in the window.
            sameIpCount = recent.size() + 1;
        }

        List<Integer> providerHistory = reviewRepository
                .findByRevieweeIdAndReviewerRole(providerId, ReviewerRole.CUSTOMER).stream()
                .filter(Review::countsTowardAggregate)
                .map(Review::getOverallRating)
                .toList();

        Instant reviewerCreatedAt = reviewerAccountPort.accountCreatedAt(command.reviewerId())
                .orElse(null);

        FraudSignals signals = new FraudSignals(command.sourceIp(), now, sameIpCount,
                command.overall(), providerHistory, reviewerCreatedAt);
        return fraudDetector.evaluate(signals);
    }

    /** Read-only aggregate lookup for query endpoints/tests. */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> currentAggregate(UUID providerId) {
        List<Review> reviews = reviewRepository.findByRevieweeIdAndReviewerRole(
                providerId, ReviewerRole.CUSTOMER);
        return Optional.ofNullable(aggregateCalculator.aggregate(reviews, clock.instant()));
    }
}
