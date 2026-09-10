package com.homefix.rating.service;

import java.util.List;
import java.util.UUID;

/**
 * A request to submit a review (Requirement 15.3). Assembled by the REST layer from the request
 * body, the authenticated reviewer, and the source IP.
 *
 * <p>For a {@link com.homefix.rating.domain.ReviewerRole#CUSTOMER} review all five dimensions are
 * required; for a provider-to-customer review only {@link #overall} is meaningful and the other
 * dimensions are ignored.
 *
 * @param bookingId            booking the review is for
 * @param reviewerId           authenticated author of the review
 * @param overall              overall star rating (1–5)
 * @param behavior             behavior star rating (1–5, customer reviews)
 * @param quality              quality star rating (1–5, customer reviews)
 * @param timeliness           timeliness star rating (1–5, customer reviews)
 * @param pricingTransparency  pricing-transparency star rating (1–5, customer reviews)
 * @param reviewText           optional text, at most 1000 characters
 * @param attachments          up to 5 photo attachments, each at most 10 MB
 * @param sourceIp             originating IP, used by fraud detection (may be {@code null})
 */
public record SubmitReviewCommand(
        UUID bookingId,
        UUID reviewerId,
        int overall,
        int behavior,
        int quality,
        int timeliness,
        int pricingTransparency,
        String reviewText,
        List<AttachmentMetadata> attachments,
        String sourceIp) {
}
