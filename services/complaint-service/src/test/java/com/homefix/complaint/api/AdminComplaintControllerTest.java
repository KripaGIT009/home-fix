package com.homefix.complaint.api;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.homefix.complaint.domain.Complaint;
import com.homefix.complaint.domain.ComplaintCategory;
import com.homefix.complaint.domain.ComplaintStatus;
import com.homefix.complaint.domain.ServicePriority;
import com.homefix.complaint.service.ComplaintException;
import com.homefix.complaint.service.ComplaintService;

/**
 * Web-layer tests for {@link AdminComplaintController}: the JSON shape the Admin Portal's
 * {@code AdminComplaint} type reads, parameter binding, and the error envelope. Role enforcement
 * is covered by {@code ComplaintRbacConfigTest}.
 */
class AdminComplaintControllerTest {

    private static final Instant CREATED = Instant.parse("2024-06-01T12:00:00Z");
    private static final Instant SLA = Instant.parse("2024-06-04T12:00:00Z");

    private ComplaintService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ComplaintService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminComplaintController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                // Boot's auto-configured mapper writes Instants as ISO-8601; mirror it here.
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    private static Complaint complaint() {
        return Complaint.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ComplaintCategory.LATE_ARRIVAL, ServicePriority.STANDARD,
                "Plumber arrived three hours late", CREATED, SLA);
    }

    @Test
    void listReturnsThePortalShapeAsABareArray() throws Exception {
        Complaint c = complaint();
        when(service.searchForAdmin(isNull(), isNull())).thenReturn(List.of(c));

        mvc.perform(get("/admin/complaints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(c.getId().toString()))
                .andExpect(jsonPath("$[0].bookingReference").value(nullValue()))
                .andExpect(jsonPath("$[0].raisedByName").value(nullValue()))
                .andExpect(jsonPath("$[0].category").value("LATE_ARRIVAL"))
                .andExpect(jsonPath("$[0].summary").value("Plumber arrived three hours late"))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].slaDueAt").value(SLA.toString()))
                .andExpect(jsonPath("$[0].createdAt").value(CREATED.toString()));
    }

    @Test
    void listPassesSearchAndStatusThrough() throws Exception {
        when(service.searchForAdmin("late", ComplaintStatus.ESCALATED)).thenReturn(List.of());

        mvc.perform(get("/admin/complaints").param("search", "late").param("status", "ESCALATED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        verify(service).searchForAdmin("late", ComplaintStatus.ESCALATED);
    }

    @Test
    void updateDelegatesAndReturnsTheUpdatedComplaint() throws Exception {
        Complaint c = complaint();
        c.resolve(SLA);
        c.recordResolutionNote("Refunded");
        when(service.adminUpdate(eq(c.getId()), eq(ComplaintStatus.RESOLVED), eq("Refunded")))
                .thenReturn(c);

        mvc.perform(patch("/admin/complaints/{id}", c.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"resolutionNote\":\"Refunded\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(c.getId().toString()))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                // The note is returned, not only stored.
                .andExpect(jsonPath("$.resolutionNote").value("Refunded"));
    }

    @Test
    void updateWithoutStatusIsAValidationError() throws Exception {
        mvc.perform(patch("/admin/complaints/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionNote\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(service);
    }

    @Test
    void illegalTransitionSurfacesAs409WithTheErrorEnvelope() throws Exception {
        when(service.adminUpdate(any(), any(), any())).thenThrow(
                ComplaintException.invalidTransition("cannot change a complaint that is CLOSED"));

        mvc.perform(patch("/admin/complaints/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_COMPLAINT_TRANSITION"));
    }
}
