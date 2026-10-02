package com.homefix.complaint.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.homefix.complaint.api.dto.ChangeStatusRequest;
import com.homefix.complaint.api.dto.RefundRequest;
import com.homefix.complaint.domain.Complaint;
import com.homefix.complaint.domain.ComplaintCategory;
import com.homefix.complaint.domain.ComplaintRefund;
import com.homefix.complaint.domain.ComplaintStatus;
import com.homefix.complaint.domain.ServicePriority;
import com.homefix.complaint.service.ApproveRefundCommand;
import com.homefix.complaint.service.ComplaintException;
import com.homefix.complaint.service.ComplaintService;
import com.homefix.complaint.service.ComplaintStats;
import com.homefix.complaint.service.CreateComplaintCommand;
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
 * Web-layer tests for {@link ComplaintController}. The no-path-variable endpoints (create, stats)
 * run through standalone MockMvc; the path-variable moderation endpoints (status/refund/dispute)
 * are exercised by direct method calls because the build does not enable the {@code -parameters}
 * flag. The authenticated customer is derived from the {@link SecurityContextHolder} principal.
 */
class ComplaintControllerTest {

    private ComplaintService service;
    private ComplaintController controller;
    private MockMvc mvc;
    private final UUID customer = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(ComplaintService.class);
        controller = new ComplaintController(service);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String name) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                name, "n/a", AuthorityUtils.createAuthorityList("ROLE_CUSTOMER")));
    }

    private Complaint complaint() {
        return Complaint.open(UUID.randomUUID(), customer, UUID.randomUUID(), UUID.randomUUID(),
                ComplaintCategory.POOR_QUALITY, ServicePriority.STANDARD, "bad job",
                Instant.now(), Instant.now().plus(Duration.ofHours(72)));
    }

    @Test
    void create_returns201_withAuthenticatedCustomer() throws Exception {
        authenticate(customer.toString());
        when(service.createComplaint(any(CreateComplaintCommand.class))).thenReturn(complaint());

        String body = """
                {"bookingId":"%s","providerId":"%s","category":"POOR_QUALITY","priority":"STANDARD",
                 "description":"bad job"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mvc.perform(post("/complaints").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));

        var captor = org.mockito.ArgumentCaptor.forClass(CreateComplaintCommand.class);
        verify(service).createComplaint(captor.capture());
        assertThat(captor.getValue().customerId()).isEqualTo(customer);
    }

    @Test
    void create_missingRequiredField_returns400ValidationEnvelope() throws Exception {
        authenticate(customer.toString());
        // category omitted -> @NotNull.
        String body = """
                {"bookingId":"%s","priority":"STANDARD","description":"x"}
                """.formatted(UUID.randomUUID());

        mvc.perform(post("/complaints").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void stats_returnsAggregatedProjection() throws Exception {
        authenticate(customer.toString());
        ComplaintStats stats = new ComplaintStats(
                Map.of(ComplaintCategory.POOR_QUALITY, 3L), 5, 4, 0.8, Duration.ofHours(2));
        when(service.aggregateStats()).thenReturn(stats);

        mvc.perform(get("/complaints/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalComplaints").value(5))
                .andExpect(jsonPath("$.resolvedComplaints").value(4))
                .andExpect(jsonPath("$.resolutionRate").value(0.8))
                .andExpect(jsonPath("$.averageResolutionSeconds").value(7200));
    }

    @Test
    void changeStatus_delegates() {
        when(service.changeStatus(any(), eq(ComplaintStatus.IN_PROGRESS))).thenReturn(complaint());
        UUID id = UUID.randomUUID();
        controller.changeStatus(id, new ChangeStatusRequest(ComplaintStatus.IN_PROGRESS));
        verify(service).changeStatus(id, ComplaintStatus.IN_PROGRESS);
    }

    @Test
    void approveRefund_passesTheAuthenticatedAgentAndReturns202WithTheRecordedRefund() {
        UUID agent = UUID.randomUUID();
        authenticate(agent.toString());
        UUID id = UUID.randomUUID();
        ComplaintRefund recorded = ComplaintRefund.reserve(id, UUID.randomUUID(),
                new BigDecimal("50.00"), "tap leaked", agent, "req-1", Instant.now());
        recorded.markSucceeded("rf_123", Instant.now());
        when(service.approveRefund(any(ApproveRefundCommand.class))).thenReturn(recorded);

        var response = controller.approveRefund(id,
                new RefundRequest(new BigDecimal("50.00"), "tap leaked", "req-1"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().status()).isEqualTo("SUCCEEDED");
        assertThat(response.getBody().externalReference()).isEqualTo("rf_123");
        assertThat(response.getBody().approvedBy()).isEqualTo(agent);
        verify(service).approveRefund(new ApproveRefundCommand(
                id, new BigDecimal("50.00"), "tap leaked", agent, "req-1"));
    }

    @Test
    void approveRefund_overHttp_mapsARefusalToTheErrorEnvelope() throws Exception {
        authenticate(UUID.randomUUID().toString());
        when(service.approveRefund(any(ApproveRefundCommand.class))).thenThrow(
                ComplaintException.refundAlreadyRequested("complaint already has a refund"));

        mvc.perform(post("/complaints/" + UUID.randomUUID() + "/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REFUND_ALREADY_REQUESTED"));
    }

    @Test
    void dispute_delegates() {
        UUID id = UUID.randomUUID();
        when(service.markDisputed(id)).thenReturn(complaint());
        controller.dispute(id);
        verify(service).markDisputed(id);
    }

    @Test
    void create_withoutAuthentication_isUnauthorized() throws Exception {
        String body = """
                {"bookingId":"%s","category":"POOR_QUALITY","priority":"STANDARD","description":"x"}
                """.formatted(UUID.randomUUID());

        mvc.perform(post("/complaints").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withNonUuidPrincipal_isRejectedByGuard() {
        authenticate("not-a-uuid");
        assertThatThrownBy(() -> controller.create(new com.homefix.complaint.api.dto.CreateComplaintRequest(
                UUID.randomUUID(), UUID.randomUUID(), ComplaintCategory.POOR_QUALITY,
                ServicePriority.STANDARD, "x", null)))
                .isInstanceOf(ComplaintException.class)
                .satisfies(e -> assertThat(((ComplaintException) e).getErrorCode())
                        .isEqualTo("INVALID_PRINCIPAL"));
    }

    // ---- Attribution: the complaint belongs to the JWT subject, never to client input ----------

    /**
     * Attribution guard: the {@code customerId} on the command handed to the service is the
     * authenticated JWT subject, and matches none of the identifiers the client supplied in the
     * body (booking id, provider id) — so a client cannot steer attribution by choosing what it
     * sends.
     */
    @Test
    void create_attributesComplaintToJwtSubject_notToAnyIdInTheRequestBody() throws Exception {
        UUID jwtSubject = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        authenticate(jwtSubject.toString());
        when(service.createComplaint(any(CreateComplaintCommand.class))).thenReturn(complaint());

        String body = """
                {"bookingId":"%s","providerId":"%s","category":"POOR_QUALITY","priority":"STANDARD",
                 "description":"bad job"}
                """.formatted(bookingId, providerId);

        mvc.perform(post("/complaints").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        var captor = org.mockito.ArgumentCaptor.forClass(CreateComplaintCommand.class);
        verify(service).createComplaint(captor.capture());
        assertThat(captor.getValue().customerId()).isEqualTo(jwtSubject);
        assertThat(captor.getValue().customerId()).isNotIn(bookingId, providerId);
        assertThat(captor.getValue().bookingId()).isEqualTo(bookingId);
        assertThat(captor.getValue().providerId()).isEqualTo(providerId);
    }

    /**
     * The same request body posted by two different principals produces two different attributions,
     * proving the customer id is read from the security context and not from the payload.
     */
    @Test
    void create_identicalBodyFromTwoPrincipals_isAttributedToEachJwtSubject() throws Exception {
        when(service.createComplaint(any(CreateComplaintCommand.class))).thenReturn(complaint());
        String body = """
                {"bookingId":"%s","providerId":"%s","category":"POOR_QUALITY","priority":"STANDARD",
                 "description":"bad job"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        UUID customerA = UUID.randomUUID();
        authenticate(customerA.toString());
        mvc.perform(post("/complaints").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        UUID customerB = UUID.randomUUID();
        SecurityContextHolder.clearContext();
        authenticate(customerB.toString());
        mvc.perform(post("/complaints").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        var captor = org.mockito.ArgumentCaptor.forClass(CreateComplaintCommand.class);
        verify(service, org.mockito.Mockito.times(2)).createComplaint(captor.capture());
        assertThat(captor.getAllValues()).extracting(CreateComplaintCommand::customerId)
                .containsExactly(customerA, customerB);
    }

    /**
     * Structural guard for the attribution above: {@code CreateComplaintRequest} must never grow a
     * caller-supplied customer/reporter/user identifier. If one is ever added, the controller's
     * {@code currentUser()} attribution stops being the only source of truth and an explicit
     * JWT-subject assertion becomes mandatory — fail here rather than ship that hole silently.
     */
    @Test
    void createRequestDto_carriesNoCallerSuppliedIdentityField() {
        assertThat(com.homefix.complaint.api.dto.CreateComplaintRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .noneMatch(name -> name.equalsIgnoreCase("customerId")
                        || name.equalsIgnoreCase("reporterId")
                        || name.equalsIgnoreCase("userId")
                        || name.equalsIgnoreCase("raisedBy"));
    }
}
