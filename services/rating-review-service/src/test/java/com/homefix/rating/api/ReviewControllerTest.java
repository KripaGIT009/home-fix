package com.homefix.rating.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import com.homefix.rating.domain.Review;
import com.homefix.rating.domain.ReviewerRole;
import com.homefix.rating.service.ReviewService;
import com.homefix.rating.service.SubmitReviewCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web-layer tests for {@link ReviewController}. The submission endpoints (no {@code @PathVariable})
 * are exercised through standalone MockMvc with a JWT-style principal and the client IP resolved
 * from {@code X-Forwarded-For}; the moderation endpoints (which use {@code @PathVariable}) are
 * exercised by direct invocation because the build does not enable the {@code -parameters} flag.
 */
class ReviewControllerTest {

    private ReviewService reviewService;
    private ReviewController controller;
    private MockMvc mvc;

    private final UUID reviewer = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reviewService = mock(ReviewService.class);
        controller = new ReviewController(reviewService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                reviewer.toString(), "n/a", AuthorityUtils.createAuthorityList("ROLE_CUSTOMER")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Review review(UUID booking, UUID reviewee, ReviewerRole role, boolean flagged) {
        return role == ReviewerRole.CUSTOMER
                ? Review.customerReview(booking, reviewer, reviewee, 5, 5, 5, 5, 5, "ok", "1.2.3.4",
                        flagged, Instant.now())
                : Review.providerReview(booking, reviewer, reviewee, 5, "ok", "1.2.3.4", flagged,
                        Instant.now());
    }

    @Test
    void submit_returns201_andResolvesClientIpFromForwardedHeader() throws Exception {
        UUID booking = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        when(reviewService.submitCustomerReview(any(SubmitReviewCommand.class)))
                .thenReturn(review(booking, provider, ReviewerRole.CUSTOMER, false));

        String body = """
                {"bookingId":"%s","overall":5,"behavior":5,"quality":5,"timeliness":5,
                 "pricingTransparency":5,"reviewText":"ok"}
                """.formatted(booking);

        mvc.perform(post("/reviews").header("X-Forwarded-For", "203.0.113.9, 10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewerRole").value("CUSTOMER"))
                .andExpect(jsonPath("$.flagged").value(false));

        var captor = org.mockito.ArgumentCaptor.forClass(SubmitReviewCommand.class);
        verify(reviewService).submitCustomerReview(captor.capture());
        assertThat(captor.getValue().reviewerId()).isEqualTo(reviewer);
        assertThat(captor.getValue().sourceIp()).isEqualTo("203.0.113.9");
    }

    @Test
    void submitProviderRating_returns201() throws Exception {
        UUID booking = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        when(reviewService.submitProviderReview(any(SubmitReviewCommand.class)))
                .thenReturn(review(booking, customer, ReviewerRole.PROVIDER, false));

        String body = """
                {"bookingId":"%s","overall":5,"behavior":5,"quality":5,"timeliness":5,
                 "pricingTransparency":5}
                """.formatted(booking);

        mvc.perform(post("/reviews/customer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewerRole").value("PROVIDER"));
    }

    @Test
    void submit_invalidStarRating_returns400ValidationEnvelope() throws Exception {
        String body = """
                {"bookingId":"%s","overall":6,"behavior":5,"quality":5,"timeliness":5,
                 "pricingTransparency":5}
                """.formatted(UUID.randomUUID());

        mvc.perform(post("/reviews").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void approve_delegatesToService() {
        UUID reviewId = UUID.randomUUID();
        when(reviewService.approveReview(reviewId))
                .thenReturn(review(UUID.randomUUID(), UUID.randomUUID(), ReviewerRole.CUSTOMER, false));

        controller.approve(reviewId);

        verify(reviewService).approveReview(reviewId);
    }

    @Test
    void remove_delegatesToServiceWithActingAdminAndReason() {
        UUID reviewId = UUID.randomUUID();
        var response = controller.remove(reviewId,
                new com.homefix.rating.api.dto.RemoveReviewRequest("confirmed spam"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(reviewService).removeReview(eq(reviewId), eq(reviewer), eq("confirmed spam"));
    }
}
