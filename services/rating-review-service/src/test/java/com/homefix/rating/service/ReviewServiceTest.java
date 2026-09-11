package com.homefix.rating.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.homefix.rating.audit.AuditLogEntry;
import com.homefix.rating.config.RatingProperties;
import com.homefix.rating.domain.Review;
import com.homefix.rating.domain.ReviewPrompt;
import com.homefix.rating.domain.ReviewerRole;
import com.homefix.rating.event.PaymentCompletedEvent;
import com.homefix.rating.support.FixedReviewerAccountPort;
import com.homefix.rating.support.InMemoryAuditLogRepository;
import com.homefix.rating.support.InMemoryReviewPromptRepository;
import com.homefix.rating.support.InMemoryReviewRepository;
import com.homefix.rating.support.RecordingAdminAlertPort;
import com.homefix.rating.support.RecordingProviderRatingPort;
import com.homefix.rating.support.RecordingReviewSubmittedPublisher;

/**
 * Unit tests for the rating-and-review orchestration (Requirement 15).
 *
 * <p>Covers: expired-window rejection (15.2, Property 15), weighted aggregate over mixed recency
 * (15.4, Property 16), fraud-flag triggers and their exclusion from the aggregate (15.5,
 * Property 17), the {@code ReviewSubmitted} event only for non-flagged reviews (15.6), auto-flag
 * UNDER_REVIEW below threshold (15.7, 15.8), and Admin removal recalculating the aggregate with an
 * Audit_Log entry (15.9). Example-based; no Spring context, DB, or Kafka.
 */
class ReviewServiceTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");
    private static final String IP = "198.51.100.20";

    private final InMemoryReviewRepository reviews = new InMemoryReviewRepository();
    private final InMemoryReviewPromptRepository prompts = new InMemoryReviewPromptRepository();
    private final InMemoryAuditLogRepository auditLog = new InMemoryAuditLogRepository();
    private final RecordingReviewSubmittedPublisher publisher = new RecordingReviewSubmittedPublisher();
    private final RecordingProviderRatingPort providerRating = new RecordingProviderRatingPort();
    private final RecordingAdminAlertPort adminAlert = new RecordingAdminAlertPort();
    private final FixedReviewerAccountPort accounts = new FixedReviewerAccountPort();
    private final RatingProperties properties = new RatingProperties();

    private ReviewService serviceAt(Instant now) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return new ReviewService(reviews, prompts, auditLog, publisher, providerRating, adminAlert,
                accounts, properties, clock);
    }

    private ReviewPrompt openCustomerPrompt(UUID bookingId, UUID customerId, UUID providerId,
                                            Instant createdAt) {
        ReviewPrompt prompt = ReviewPrompt.open(bookingId, UUID.randomUUID(), ReviewerRole.CUSTOMER,
                customerId, providerId, createdAt, createdAt.plus(properties.getReviewWindow()));
        return prompts.save(prompt);
    }

    private ReviewPrompt openProviderPrompt(UUID bookingId, UUID providerId, UUID customerId,
                                            Instant createdAt) {
        ReviewPrompt prompt = ReviewPrompt.open(bookingId, UUID.randomUUID(), ReviewerRole.PROVIDER,
                providerId, customerId, createdAt, createdAt.plus(properties.getReviewWindow()));
        return prompts.save(prompt);
    }

    private SubmitReviewCommand command(UUID bookingId, UUID customerId, int stars) {
        return new SubmitReviewCommand(bookingId, customerId, stars, stars, stars, stars, stars,
                "fine", List.of(), IP);
    }

    // ---- Prompt creation on PaymentCompleted (15.1, 15.10) -------------------------------------

    @Test
    void paymentCompletedOpensPairOfSevenDayPrompts() {
        ReviewService service = serviceAt(NOW);
        UUID booking = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        PaymentCompletedEvent event = new PaymentCompletedEvent(UUID.randomUUID(), booking, customer,
                provider, new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("90.00"),
                "UPI", NOW);

        service.openReviewPrompts(event);

        assertThat(prompts.findAll()).hasSize(2);
        ReviewPrompt customerPrompt = prompts.findByBookingIdAndReviewerRole(booking, ReviewerRole.CUSTOMER).orElseThrow();
        assertThat(customerPrompt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(prompts.findByBookingIdAndReviewerRole(booking, ReviewerRole.PROVIDER)).isPresent();
    }

    @Test
    void redeliveredPaymentCompletedDoesNotDuplicatePrompts() {
        ReviewService service = serviceAt(NOW);
        UUID paymentId = UUID.randomUUID();
        PaymentCompletedEvent event = new PaymentCompletedEvent(paymentId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE,
                "UPI", NOW);

        service.openReviewPrompts(event);
        service.openReviewPrompts(event);

        assertThat(prompts.findAll()).hasSize(2);
    }

    // ---- Expired window rejection (15.2, Property 15) ------------------------------------------

    @Test
    void submissionAfterWindowExpiryIsRejected() {
        UUID booking = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        Instant created = NOW;
        openCustomerPrompt(booking, customer, provider, created);

        // 7 days + 1 second after the prompt opened -> window closed.
        ReviewService service = serviceAt(created.plus(Duration.ofDays(7)).plusSeconds(1));

        assertThatThrownBy(() -> service.submitCustomerReview(command(booking, customer, 5)))
                .isInstanceOf(ReviewException.class)
                .satisfies(e -> assertThat(((ReviewException) e).getErrorCode()).isEqualTo("REVIEW_WINDOW_CLOSED"));
        assertThat(reviews.findAll()).isEmpty();
    }

    @Test
    void submissionWithinWindowIsAccepted() {
        UUID booking = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        openCustomerPrompt(booking, customer, provider, NOW);
        ReviewService service = serviceAt(NOW.plus(Duration.ofDays(6)));

        Review review = service.submitCustomerReview(command(booking, customer, 5));

        assertThat(review.isFlagged()).isFalse();
        assertThat(review.getRevieweeId()).isEqualTo(provider);
        assertThat(publisher.published()).hasSize(1);
    }

    // ---- Weighted aggregate with mixed recency (15.4, Property 16) -----------------------------

    @Test
    void aggregateReflectsWeightedMixOfRecentAndOlderReviews() {
        UUID provider = UUID.randomUUID();
        // Seed an older 3-star review (200 days old) directly.
        reviews.save(Review.customerReview(UUID.randomUUID(), UUID.randomUUID(), provider,
                3, 3, 3, 3, 3, null, "1.1.1.1", false, NOW.minus(Duration.ofDays(200))));

        // Submit a recent 5-star review now.
        UUID booking = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        openCustomerPrompt(booking, customer, provider, NOW);
        ReviewService service = serviceAt(NOW);
        service.submitCustomerReview(command(booking, customer, 5));

        // (1.5*5 + 1.0*3) / (1.5 + 1.0) = 10.5 / 2.5 = 4.20
        assertThat(providerRating.lastAggregate(provider)).isEqualByComparingTo("4.20");
    }

    // ---- Fraud flag triggers + exclusion from aggregate (15.5, Property 17) --------------------

    @Test
    void freshAccountReviewIsFlaggedExcludedFromAggregateAndNotPublished() {
        UUID provider = UUID.randomUUID();
        // Provider already has two clean 5-star reviews -> current aggregate 5.00.
        reviews.save(Review.customerReview(UUID.randomUUID(), UUID.randomUUID(), provider,
                5, 5, 5, 5, 5, null, "1.1.1.1", false, NOW.minus(Duration.ofDays(10))));
        reviews.save(Review.customerReview(UUID.randomUUID(), UUID.randomUUID(), provider,
                5, 5, 5, 5, 5, null, "2.2.2.2", false, NOW.minus(Duration.ofDays(5))));

        UUID booking = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        openCustomerPrompt(booking, customer, provider, NOW);
        // Reviewer account created 2 hours ago -> fresh-account fraud trigger.
        accounts.set(customer, NOW.minus(Duration.ofHours(2)));
        ReviewService service = serviceAt(NOW);

        Review review = service.submitCustomerReview(command(booking, customer, 1));

        assertThat(review.isFlagged()).isTrue();
        // Flagged review not published (15.6) and excluded from aggregate (Property 17):
        assertThat(publisher.published()).isEmpty();
        // The flagged 1-star did not touch the aggregate; recalculation was not triggered for a
        // flagged review, so the port never saw a drop.
        assertThat(providerRating.lastAggregate(provider)).isNull();
    }

    @Test
    void sameIpBurstFlagsSecondReview() {
        UUID provider = UUID.randomUUID();
        // First review from IP now (via seed with same IP within the 1h window).
        reviews.save(Review.customerReview(UUID.randomUUID(), UUID.randomUUID(), provider,
                4, 4, 4, 4, 4, null, IP, false, NOW.minus(Duration.ofMinutes(10))));

        UUID booking = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        openCustomerPrompt(booking, customer, provider, NOW);
        ReviewService service = serviceAt(NOW);

        Review review = service.submitCustomerReview(command(booking, customer, 4));

        // One prior review from IP + this one = 2 within the window -> same-IP burst flag.
        assertThat(review.isFlagged()).isTrue();
    }

    // ---- Auto-flag UNDER_REVIEW below threshold (15.7, 15.8) -----------------------------------

    @Test
    void aggregateBelowThresholdFlagsProviderAndAlertsAdminOnce() {
        UUID provider = UUID.randomUUID();
        UUID booking = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        openCustomerPrompt(booking, customer, provider, NOW);
        ReviewService service = serviceAt(NOW);

        // Single 2-star review -> aggregate 2.00 (< 3.0 default threshold).
        service.submitCustomerReview(command(booking, customer, 2));

        assertThat(providerRating.lastAggregate(provider)).isEqualByComparingTo("2.00");
        assertThat(providerRating.isUnderReview(provider)).isTrue();
        assertThat(adminAlert.alerted).containsExactly(provider);

        // A second low review recalculates but must not re-alert (already UNDER_REVIEW).
        UUID booking2 = UUID.randomUUID();
        UUID customer2 = UUID.randomUUID();
        openCustomerPrompt(booking2, customer2, provider, NOW);
        service.submitCustomerReview(command(booking2, customer2, 1));

        assertThat(adminAlert.alerted).containsExactly(provider);
    }

    // ---- Admin removal recalculates aggregate + audits (15.9) ----------------------------------

    @Test
    void adminRemovalRecalculatesAggregateAndWritesAuditLog() {
        UUID provider = UUID.randomUUID();
        // Two reviews: a 5-star (kept) and a 1-star (to be removed).
        Review keep = Review.customerReview(UUID.randomUUID(), UUID.randomUUID(), provider,
                5, 5, 5, 5, 5, null, "1.1.1.1", false, NOW.minus(Duration.ofDays(10)));
        Review remove = Review.customerReview(UUID.randomUUID(), UUID.randomUUID(), provider,
                1, 1, 1, 1, 1, null, "2.2.2.2", false, NOW.minus(Duration.ofDays(5)));
        reviews.save(keep);
        reviews.save(remove);

        ReviewService service = serviceAt(NOW);
        UUID admin = UUID.randomUUID();

        service.removeReview(remove.getId(), admin, "confirmed spam");

        // After removal only the 5-star remains -> aggregate 5.00.
        assertThat(providerRating.lastAggregate(provider)).isEqualByComparingTo("5.00");
        assertThat(reviews.findById(remove.getId()).orElseThrow().isActive()).isFalse();

        List<AuditLogEntry> entries = auditLog.findAll();
        assertThat(entries).hasSize(1);
        AuditLogEntry entry = entries.get(0);
        assertThat(entry.getActorId()).isEqualTo(admin);
        assertThat(entry.getReason()).isEqualTo("confirmed spam");
        assertThat(entry.getEntityId()).isEqualTo(remove.getId());
        assertThat(entry.getAction()).isEqualTo("REVIEW_REMOVED");
    }

    @Test
    void adminRemovalRequiresReason() {
        Review review = Review.customerReview(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, 1, 1, 1, 1, null, "2.2.2.2", false, NOW);
        reviews.save(review);
        ReviewService service = serviceAt(NOW);

        assertThatThrownBy(() -> service.removeReview(review.getId(), UUID.randomUUID(), "  "))
                .isInstanceOf(ReviewException.class);
    }

    @Test
    void approvingFlaggedReviewReentersAggregateAndPublishes() {
        UUID provider = UUID.randomUUID();
        // A flagged 5-star review sitting out of the aggregate.
        Review flagged = Review.customerReview(UUID.randomUUID(), UUID.randomUUID(), provider,
                5, 5, 5, 5, 5, null, "1.1.1.1", true, NOW.minus(Duration.ofDays(3)));
        reviews.save(flagged);
        ReviewService service = serviceAt(NOW);

        service.approveReview(flagged.getId());

        assertThat(reviews.findById(flagged.getId()).orElseThrow().isFlagged()).isFalse();
        assertThat(providerRating.lastAggregate(provider)).isEqualByComparingTo("5.00");
        assertThat(publisher.published()).extracting(Review::getId).containsExactly(flagged.getId());
    }

    // ---- Payload validation (15.3) -------------------------------------------------------------

    @Test
    void invalidStarRatingIsRejected() {
        UUID booking = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        openCustomerPrompt(booking, customer, UUID.randomUUID(), NOW);
        ReviewService service = serviceAt(NOW);

        SubmitReviewCommand bad = new SubmitReviewCommand(booking, customer, 6, 5, 5, 5, 5, null,
                List.of(), IP);
        assertThatThrownBy(() -> service.submitCustomerReview(bad))
                .isInstanceOf(ReviewException.class)
                .satisfies(e -> assertThat(((ReviewException) e).getErrorCode()).isEqualTo("VALIDATION_ERROR"));
    }

    @Test
    void oversizedAttachmentIsRejected() {
        UUID booking = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        openCustomerPrompt(booking, customer, UUID.randomUUID(), NOW);
        ReviewService service = serviceAt(NOW);

        SubmitReviewCommand bad = new SubmitReviewCommand(booking, customer, 5, 5, 5, 5, 5, null,
                List.of(new AttachmentMetadata("big.jpg", 11L * 1024 * 1024)), IP);
        assertThatThrownBy(() -> service.submitCustomerReview(bad))
                .isInstanceOf(ReviewException.class);
    }

    // ---- Reviewer attribution: a review must belong to the caller (Defect B) -------------------

    @Test
    void customerReviewFromSomebodyOtherThanTheInvitedReviewerIsRejectedWith403() {
        UUID booking = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        openCustomerPrompt(booking, customer, provider, NOW);
        ReviewService service = serviceAt(NOW);

        UUID impostor = UUID.randomUUID();
        assertThatThrownBy(() -> service.submitCustomerReview(command(booking, impostor, 1)))
                .isInstanceOf(ReviewException.class)
                .satisfies(e -> {
                    assertThat(((ReviewException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((ReviewException) e).getErrorCode()).isEqualTo("NOT_INVITED_REVIEWER");
                });

        // Nothing was persisted, published, or allowed to move the provider's aggregate.
        assertThat(reviews.findAll()).isEmpty();
        assertThat(publisher.published()).isEmpty();
        assertThat(prompts.findByBookingIdAndReviewerRole(booking, ReviewerRole.CUSTOMER)
                .orElseThrow().isFulfilled()).isFalse();
    }

    @Test
    void customerReviewFromTheInvitedReviewerIsAcceptedAndAttributedToThem() {
        UUID booking = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        openCustomerPrompt(booking, customer, provider, NOW);
        ReviewService service = serviceAt(NOW);

        Review review = service.submitCustomerReview(command(booking, customer, 5));

        assertThat(review.getReviewerId()).isEqualTo(customer);
        assertThat(review.getRevieweeId()).isEqualTo(provider);
    }

    @Test
    void providerReviewFromSomebodyOtherThanTheInvitedReviewerIsRejectedWith403() {
        UUID booking = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        openProviderPrompt(booking, provider, customer, NOW);
        ReviewService service = serviceAt(NOW);

        assertThatThrownBy(() -> service.submitProviderReview(command(booking, customer, 5)))
                .isInstanceOf(ReviewException.class)
                .satisfies(e -> assertThat(((ReviewException) e).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(reviews.findAll()).isEmpty();
    }

    @Test
    void providerReviewFromTheInvitedProviderIsAcceptedAndAttributedToThem() {
        UUID booking = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        openProviderPrompt(booking, provider, customer, NOW);
        ReviewService service = serviceAt(NOW);

        Review review = service.submitProviderReview(command(booking, provider, 4));

        assertThat(review.getReviewerId()).isEqualTo(provider);
        assertThat(review.getRevieweeId()).isEqualTo(customer);
    }

    @Test
    void customerReviewWithNoReviewerIsRejectedWith403() {
        UUID booking = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        openCustomerPrompt(booking, customer, UUID.randomUUID(), NOW);
        ReviewService service = serviceAt(NOW);

        SubmitReviewCommand anonymous = new SubmitReviewCommand(booking, null, 5, 5, 5, 5, 5, null,
                List.of(), IP);
        assertThatThrownBy(() -> service.submitCustomerReview(anonymous))
                .isInstanceOf(ReviewException.class)
                .satisfies(e -> assertThat(((ReviewException) e).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }
}
