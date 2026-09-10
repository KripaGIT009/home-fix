package com.homefix.rating.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.homefix.rating.account.StubReviewerAccountAdapter;
import com.homefix.rating.alert.LoggingAdminAlertAdapter;
import com.homefix.rating.config.RatingProperties;
import com.homefix.rating.domain.Review;
import com.homefix.rating.event.ReviewSubmittedEvent;
import com.homefix.rating.event.ReviewSubmittedPublisher;
import com.homefix.rating.provider.LoggingProviderRatingAdapter;
import com.homefix.rating.service.ReviewException;
import com.homefix.shared.outbox.OutboxEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for the Rating &amp; Review outbound adapters, the outbox publisher seam, the tunable
 * properties, and the domain exception factories.
 */
class RatingAdaptersTest {

    @Test
    void loggingProviderRatingAdapter_flagsUnderReviewOnlyOncePerProvider() {
        LoggingProviderRatingAdapter adapter = new LoggingProviderRatingAdapter();
        UUID provider = UUID.randomUUID();

        adapter.updateAggregateRating(provider, new BigDecimal("2.50"));
        assertThat(adapter.flagUnderReview(provider)).isTrue();
        // Idempotent: a second flag for the same provider returns false (already under review).
        assertThat(adapter.flagUnderReview(provider)).isFalse();
    }

    @Test
    void loggingAdminAlertAdapter_providerBelowThreshold_doesNotThrow() {
        new LoggingAdminAlertAdapter().providerBelowThreshold(
                UUID.randomUUID(), new BigDecimal("2.10"), new BigDecimal("3.0"));
    }

    @Test
    void stubReviewerAccountAdapter_returnsEmptySoFreshAccountDetectionIsSkipped() {
        assertThat(new StubReviewerAccountAdapter().accountCreatedAt(UUID.randomUUID())).isEmpty();
    }

    @Test
    void reviewSubmittedPublisher_publishesEventThroughOutbox() {
        OutboxEventPublisher outbox = mock(OutboxEventPublisher.class);
        ReviewSubmittedPublisher publisher = new ReviewSubmittedPublisher(outbox);
        Review review = Review.customerReview(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                5, 5, 5, 5, 5, "great", "1.1.1.1", false, Instant.now());

        publisher.publish(review);

        verify(outbox).publish(eq(ReviewSubmittedEvent.AGGREGATE_TYPE), eq(review.getId()),
                eq(ReviewSubmittedEvent.EVENT_TYPE), any(ReviewSubmittedEvent.class));
    }

    @Test
    void ratingProperties_exposeDefaultsAndNestedConfig() {
        RatingProperties p = new RatingProperties();
        assertThat(p.getReviewWindow()).isEqualTo(Duration.ofDays(7));
        assertThat(p.getRecentWindow()).isEqualTo(Duration.ofDays(90));
        assertThat(p.getRecentWeight()).isEqualByComparingTo("1.5");
        assertThat(p.getOlderWeight()).isEqualByComparingTo("1.0");
        assertThat(p.getUnderReviewThreshold()).isEqualByComparingTo("3.0");
        assertThat(p.getMaxReviewTextLength()).isEqualTo(1000);
        assertThat(p.getFraud().getSameIpThreshold()).isEqualTo(2);
        assertThat(p.getFraud().getSameIpWindow()).isEqualTo(Duration.ofHours(1));
        assertThat(p.getFraud().getDeviationSdThreshold()).isEqualTo(2.0);
        assertThat(p.getFraud().getMinAccountAge()).isEqualTo(Duration.ofHours(24));
        assertThat(p.getAttachments().getMaxCount()).isEqualTo(5);
        assertThat(p.getAttachments().getMaxBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(p.getTopics().getPaymentCompleted()).isEqualTo("PaymentCompleted");
        assertThat(p.getTopics().getReviewSubmitted()).isEqualTo("ReviewSubmitted");

        // Setters round-trip.
        p.setReviewWindow(Duration.ofDays(14));
        p.setRecentWindow(Duration.ofDays(30));
        p.setRecentWeight(new BigDecimal("2.0"));
        p.setOlderWeight(new BigDecimal("0.5"));
        p.setUnderReviewThreshold(new BigDecimal("2.5"));
        p.setMaxReviewTextLength(500);
        RatingProperties.Fraud fraud = new RatingProperties.Fraud();
        fraud.setSameIpThreshold(3);
        fraud.setSameIpWindow(Duration.ofHours(2));
        fraud.setDeviationSdThreshold(3.0);
        fraud.setMinAccountAge(Duration.ofHours(48));
        p.setFraud(fraud);
        RatingProperties.Attachments attachments = new RatingProperties.Attachments();
        attachments.setMaxCount(3);
        attachments.setMaxBytes(2048L);
        p.setAttachments(attachments);
        RatingProperties.Topics topics = new RatingProperties.Topics();
        topics.setPaymentCompleted("PC");
        topics.setReviewSubmitted("RS");
        p.setTopics(topics);

        assertThat(p.getReviewWindow()).isEqualTo(Duration.ofDays(14));
        assertThat(p.getRecentWeight()).isEqualByComparingTo("2.0");
        assertThat(p.getMaxReviewTextLength()).isEqualTo(500);
        assertThat(p.getFraud().getSameIpThreshold()).isEqualTo(3);
        assertThat(p.getAttachments().getMaxCount()).isEqualTo(3);
        assertThat(p.getTopics().getReviewSubmitted()).isEqualTo("RS");
    }

    @Test
    void reviewException_factoriesCarryStatusAndErrorCode() {
        assertThat(ReviewException.validation("x").getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ReviewException.validation("x").getErrorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(ReviewException.notFound("x").getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ReviewException.windowClosed("x").getErrorCode()).isEqualTo("REVIEW_WINDOW_CLOSED");
        assertThat(ReviewException.duplicate("x").getStatus()).isEqualTo(HttpStatus.CONFLICT);

        ReviewException withDetails = new ReviewException(HttpStatus.BAD_REQUEST, "E", "m",
                java.util.List.of("d1", "d2"));
        assertThat(withDetails.getDetails()).containsExactly("d1", "d2");
    }
}
