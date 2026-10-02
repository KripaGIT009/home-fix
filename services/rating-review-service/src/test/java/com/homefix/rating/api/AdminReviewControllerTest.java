package com.homefix.rating.api;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.homefix.rating.domain.ModerationStatus;
import com.homefix.rating.domain.Review;
import com.homefix.rating.service.ModerationAction;
import com.homefix.rating.service.ReviewException;
import com.homefix.rating.service.ReviewService;

/**
 * Web-layer tests for {@link AdminReviewController}: the JSON shape the Admin Portal's
 * {@code AdminReview} type reads, the status filter binding, the acting admin passed to the
 * service, and the error envelope. Role enforcement is covered by {@code RatingRbacConfigTest}.
 */
class AdminReviewControllerTest {

    private static final Instant SUBMITTED = Instant.parse("2024-06-01T12:00:00Z");

    private final UUID admin = UUID.randomUUID();
    private ReviewService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ReviewService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminReviewController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                // Boot's auto-configured mapper writes Instants as ISO-8601; mirror it here.
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                admin.toString(), "n/a", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static Review review(boolean flagged) {
        return Review.customerReview(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                2, 2, 2, 2, 2, "Left a mess", "1.2.3.4", flagged, SUBMITTED);
    }

    @Test
    void listReturnsThePortalShapeAsABareArray() throws Exception {
        Review r = review(true);
        when(service.listForAdmin(isNull())).thenReturn(List.of(r));

        mvc.perform(get("/admin/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(r.getId().toString()))
                .andExpect(jsonPath("$[0].bookingReference").value(nullValue()))
                .andExpect(jsonPath("$[0].reviewerName").value(nullValue()))
                .andExpect(jsonPath("$[0].providerName").value(nullValue()))
                .andExpect(jsonPath("$[0].rating").value(2))
                .andExpect(jsonPath("$[0].comment").value("Left a mess"))
                .andExpect(jsonPath("$[0].status").value("FLAGGED"))
                .andExpect(jsonPath("$[0].flagReason").doesNotExist())
                .andExpect(jsonPath("$[0].createdAt").value(SUBMITTED.toString()));
    }

    @Test
    void listPassesTheStatusFilterThrough() throws Exception {
        when(service.listForAdmin(ModerationStatus.REMOVED)).thenReturn(List.of());

        mvc.perform(get("/admin/reviews").param("status", "REMOVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        verify(service).listForAdmin(ModerationStatus.REMOVED);
    }

    @Test
    void moderatePassesTheActingAdminAndReturnsTheUpdatedReview() throws Exception {
        Review r = review(false);
        r.deactivate();
        when(service.moderate(eq(r.getId()), eq(ModerationAction.REMOVE), eq(admin), eq("spam")))
                .thenReturn(r);

        mvc.perform(post("/admin/reviews/{id}/moderate", r.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REMOVE\",\"reason\":\"spam\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(r.getId().toString()))
                .andExpect(jsonPath("$.status").value("REMOVED"));
    }

    @Test
    void moderateWithoutAnActionIsAValidationError() throws Exception {
        mvc.perform(post("/admin/reviews/{id}/moderate", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"spam\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(service);
    }

    @Test
    void publishingARemovedReviewSurfacesAs409() throws Exception {
        when(service.moderate(any(), any(), any(), any()))
                .thenThrow(ReviewException.alreadyRemoved("removed"));

        mvc.perform(post("/admin/reviews/{id}/moderate", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PUBLISH\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_REMOVED"));
    }
}
