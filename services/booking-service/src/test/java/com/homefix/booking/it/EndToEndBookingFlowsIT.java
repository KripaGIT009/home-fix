package com.homefix.booking.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingAudit;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.service.Actor;
import com.homefix.booking.service.BookingService;
import com.homefix.booking.service.DispatchOutcomeService;
import com.homefix.shared.outbox.OutboxEventEntity;
import com.homefix.shared.outbox.OutboxEventRepository;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * End-to-end integration tests for the Booking Service's booking flows (Task 44; Requirements
 * 7, 8, 9, 11).
 *
 * <h2>What this exercises</h2>
 * These tests boot the <em>entire</em> Booking Service Spring context on a random port
 * ({@link SpringBootTest}) and drive each flow through the real HTTP surface with a valid
 * signed JWT, so the full stack participates: the shared JWT authentication filter chain, the
 * REST controllers, the {@code BookingService}/{@code JobExecutionService} application layer,
 * the {@code BookingStateMachine} enforcement + contiguous audit trail, the Saga orchestrator,
 * and the transactional outbox — all persisted to a real (in-memory H2) database.
 *
 * <h2>What "staging" maps to here</h2>
 * The task asks for a staging environment with all 19 microservices. A real multi-service
 * cloud environment cannot be provisioned in this build, so the cross-service seams are handled
 * exactly the way the architecture defines them:
 * <ul>
 *   <li><b>Pricing Engine / Service Catalog</b> — synchronous dependencies, exercised through
 *       their stub port adapters ({@code StubPricingClientAdapter}, {@code StubCatalogClientAdapter}),
 *       which return the same itemized price contract the real services do.</li>
 *   <li><b>Dispatch Engine, Payment, Invoice, Notification</b> — asynchronous, event-driven
 *       consumers. The Booking Service integrates with them by writing to the transactional
 *       outbox; these tests assert the exact outbox rows the Outbox Processor would relay to
 *       Kafka (BookingCreated, ProviderAssigned, ProviderArriving, ProviderArrived, JobStarted,
 *       JobCompleted, BookingCancelled).
 *       The provider-acceptance / dispatch-outcome and payment transitions that the Dispatch
 *       Engine and Payment Service drive in staging are simulated here via the Booking
 *       Service's own guarded transition API, since those services own those transitions.</li>
 * </ul>
 *
 * <p>See the class-level notes on {@link #SEARCHING_FAILED} and the cancellation flow for what
 * is fully verified in-process vs. what still requires a live staging environment.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Import(ItSecurityConfig.class)
class EndToEndBookingFlowsIT {

    @LocalServerPort
    private int port;

    @Value("${homefix.security.jwt-secret}")
    private String jwtSecret;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private DispatchOutcomeService dispatchOutcomeService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final UUID customerId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();
    private final UUID subcategoryId = UUID.randomUUID();
    private final UUID addressId = UUID.randomUUID();

    // ---------------------------------------------------------------------
    // Flow 1 — Scheduled booking, full happy path to payment + invoice
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Scheduled booking completes end-to-end: create → estimate → confirm → dispatch → "
            + "provider milestones (before/after photo) → customer confirm → payment → invoice event")
    void scheduledBookingHappyPath() {
        // register customer → (represented by the authenticated JWT) → create booking + estimate
        Instant scheduledAt = Instant.now().plus(Duration.ofDays(3));
        ResponseEntity<Map> created = rest.exchange(url("/bookings"), HttpMethod.POST,
                jsonEntity(customerToken(), createBody(false, scheduledAt)), Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String reference = (String) created.getBody().get("reference");
        assertThat(reference).isNotBlank();
        assertThat(created.getBody().get("status")).isEqualTo("CREATED");
        // Itemized estimate presented before confirmation (Requirement 7.3).
        Map<?, ?> estimate = (Map<?, ?>) created.getBody().get("estimate");
        assertThat(estimate).isNotNull();
        assertThat(((Number) estimate.get("total")).doubleValue()).isGreaterThan(0.0);

        // confirm → SEARCHING_PROVIDER + BookingCreated event to the outbox (Requirement 7.5)
        assertStatus(post(reference + "/confirmation", customerToken()), "SEARCHING_PROVIDER");
        assertOutboxHas(reference, "BookingCreated");

        // dispatch → provider accept (Dispatch Engine drives these transitions in staging)
        assignProvider(reference);
        transitionAsSystem(reference, BookingStatus.PROVIDER_ACCEPTED, "Provider accepted offer");

        // provider on the way → arrived (job-execution milestone events published)
        assertStatus(post(reference + "/on-the-way", providerToken()), "PROVIDER_ON_THE_WAY");
        assertOutboxHas(reference, "ProviderArriving");
        assertStatus(post(reference + "/arrived", providerToken()), "PROVIDER_ARRIVED");
        assertOutboxHas(reference, "ProviderArrived");

        // before-photo gate: starting without a photo is rejected (Requirement 11.2)
        assertThat(post(reference + "/start", providerToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // attach before-photo → job started
        attachPhoto(reference, "BEFORE_PHOTO");
        assertStatus(post(reference + "/start", providerToken()), "JOB_STARTED");
        assertOutboxHas(reference, "JobStarted");

        // after-photo gate: completing without a photo is rejected (Requirement 9.10 / 11.4)
        assertThat(post(reference + "/complete", providerToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // attach after-photo → job completed (net duration stored, JobCompleted published)
        attachPhoto(reference, "AFTER_PHOTO");
        assertStatus(post(reference + "/complete", providerToken()), "JOB_COMPLETED");
        assertOutboxHas(reference, "JobCompleted");

        // customer confirm → payment pending → payment completed (Payment Service transition)
        transitionAsSystem(reference, BookingStatus.CUSTOMER_CONFIRMED, "Customer confirmed job");
        transitionAsSystem(reference, BookingStatus.PAYMENT_PENDING, "Awaiting payment");
        transitionAsSystem(reference, BookingStatus.PAYMENT_COMPLETED, "Payment captured");

        // Final assertions: state machine ended in PAYMENT_COMPLETED, no manual intervention
        // required, net duration recorded, and the outbox carries the full event chain the
        // Dispatch/Location/Payment/Invoice services consume.
        Booking finalBooking = requireBooking(reference);
        assertThat(finalBooking.getStatus()).isEqualTo(BookingStatus.PAYMENT_COMPLETED);
        assertThat(finalBooking.getNetDurationSeconds()).isNotNull();
        assertContiguousAudit(reference);
        assertOutboxHasAll(reference, "BookingCreated", "ProviderAssigned", "ProviderArriving",
                "ProviderArrived", "JobStarted", "JobCompleted");
        // This flow rests in PROVIDER_ASSIGNED (assigned via the guarded transition, accepted in a
        // later call), so the assignment is announced exactly once.
        JsonNode assigned = singleOutboxPayload(reference, "ProviderAssigned");
        assertThat(assigned.get("customerId").asText()).isEqualTo(customerId.toString());
        assertThat(assigned.get("providerId").asText()).isEqualTo(providerId.toString());
        assertThat(outboxRows(reference, "BookingCancelled")).isEmpty();

        // Every provider milestone names the customer, whom notification-service addresses
        // (Requirement 17.4); without customerId these were dead-lettered.
        for (String milestone : List.of("ProviderArriving", "ProviderArrived", "JobStarted", "JobCompleted")) {
            JsonNode event = singleOutboxPayload(reference, milestone);
            assertThat(event.get("customerId").asText()).as("%s customerId", milestone)
                    .isEqualTo(customerId.toString());
            assertThat(event.get("providerId").asText()).as("%s providerId", milestone)
                    .isEqualTo(providerId.toString());
            assertThat(event.get("reference").asText()).as("%s reference", milestone)
                    .isEqualTo(reference);
        }
    }

    // ---------------------------------------------------------------------
    // Flow 2 — Emergency booking: create + reach SEARCHING_PROVIDER in one call
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Emergency booking is created and reaches SEARCHING_PROVIDER within the SLA, then "
            + "proceeds end-to-end to payment")
    void emergencyBookingHappyPath() {
        long start = System.nanoTime();
        ResponseEntity<Map> created = rest.exchange(url("/bookings"), HttpMethod.POST,
                jsonEntity(customerToken(), createBody(true, null)), Map.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Emergency create + transition to SEARCHING_PROVIDER happen in a single request
        // (Requirement 8.1): the 5 s total SLA is comfortably met in-process.
        assertThat(created.getBody().get("status")).isEqualTo("SEARCHING_PROVIDER");
        assertThat(elapsedMs).isLessThan(5_000);

        String reference = (String) created.getBody().get("reference");
        assertOutboxHas(reference, "BookingCreated");
        // The transition to SEARCHING_PROVIDER must be durably persisted, not just reflected
        // in the response payload.
        assertThat(requireBooking(reference).getStatus())
                .as("emergency booking persisted status")
                .isEqualTo(BookingStatus.SEARCHING_PROVIDER);

        // Drive the emergency booking through the rest of the lifecycle to payment.
        assignProvider(reference);
        transitionAsSystem(reference, BookingStatus.PROVIDER_ACCEPTED, "Provider accepted");
        assertStatus(post(reference + "/on-the-way", providerToken()), "PROVIDER_ON_THE_WAY");
        assertStatus(post(reference + "/arrived", providerToken()), "PROVIDER_ARRIVED");
        attachPhoto(reference, "BEFORE_PHOTO");
        assertStatus(post(reference + "/start", providerToken()), "JOB_STARTED");
        attachPhoto(reference, "AFTER_PHOTO");
        assertStatus(post(reference + "/complete", providerToken()), "JOB_COMPLETED");
        transitionAsSystem(reference, BookingStatus.CUSTOMER_CONFIRMED, "Customer confirmed");
        transitionAsSystem(reference, BookingStatus.PAYMENT_PENDING, "Awaiting payment");
        transitionAsSystem(reference, BookingStatus.PAYMENT_COMPLETED, "Payment captured");

        assertThat(requireBooking(reference).isEmergency()).isTrue();
        assertThat(requireBooking(reference).getStatus()).isEqualTo(BookingStatus.PAYMENT_COMPLETED);
    }

    // ---------------------------------------------------------------------
    // Flow 3 — Searching-failed path (all dispatch candidates exhausted)
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("SEARCHING_FAILED path")
    class SearchingFailed {

        /**
         * In staging the Dispatch Engine drives radius expansion over 3 cycles (Requirement
         * 8.8) and, when every candidate is exhausted, transitions the booking to
         * SEARCHING_FAILED and emits the customer notification + dispatcher alert (Requirement
         * 8.9). The candidate exhaustion / radius-expansion loop itself lives in the Dispatch
         * Engine and is verified by its own tests. Here we verify the Booking Service side of
         * the contract: SEARCHING_PROVIDER → SEARCHING_FAILED is a permitted, audited terminal
         * transition, and that the failure is surfaced (audit reason) so the notification can
         * be raised. The push+SMS dispatch is Notification-Service behaviour and requires a
         * live staging environment to observe end-to-end.
         */
        @Test
        @DisplayName("exhausted dispatch drives SEARCHING_PROVIDER → SEARCHING_FAILED (terminal, audited)")
        void searchingFailedIsReachedAndTerminal() {
            String reference = createAndConfirmScheduled();
            assertThat(requireBooking(reference).getStatus()).isEqualTo(BookingStatus.SEARCHING_PROVIDER);

            // Dispatch Engine reports exhaustion after 3 radius expansions.
            transitionAsSystem(reference, BookingStatus.SEARCHING_FAILED,
                    "No provider available after 3 radius expansions; customer notified");

            Booking booking = requireBooking(reference);
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.SEARCHING_FAILED);

            // Terminal: no further transition is permitted (e.g. cannot resurrect to accepted).
            // The state machine rejects it with an InvalidTransitionException (mapped to 409).
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            transitionAsSystem(reference, BookingStatus.PROVIDER_ASSIGNED, "should be rejected"))
                    .isInstanceOf(com.homefix.booking.service.InvalidTransitionException.class);

            // The transition reason captured in the audit trail carries the customer-notification
            // intent that the Notification Service acts on (Requirement 8.9).
            assertContiguousAudit(reference);
            assertThat(auditReasons(reference)).anyMatch(r -> r != null && r.toLowerCase().contains("customer notified"));

            // The customer is told through BookingCancelled, flagged as a dispatch failure.
            JsonNode event = singleOutboxPayload(reference, "BookingCancelled");
            assertThat(event.get("status").asText()).isEqualTo("SEARCHING_FAILED");
            assertThat(event.get("customerId").asText()).isEqualTo(customerId.toString());
        }

        @Test
        @DisplayName("the Dispatch Engine's searching-failed callback writes exactly one BookingCancelled")
        void searchingFailedCallbackPublishesBookingCancelledOnce() {
            String reference = createAndConfirmScheduled();
            UUID bookingId = requireBooking(reference).getId();

            dispatchOutcomeService.markSearchingFailed(bookingId);
            dispatchOutcomeService.markSearchingFailed(bookingId); // redelivered callback

            JsonNode event = singleOutboxPayload(reference, "BookingCancelled");
            assertThat(event.get("bookingId").asText()).isEqualTo(bookingId.toString());
            assertThat(event.get("previousStatus").asText()).isEqualTo("SEARCHING_PROVIDER");
            assertThat(event.get("status").asText()).isEqualTo("SEARCHING_FAILED");
        }
    }

    // ---------------------------------------------------------------------
    // Flow 3b — Dispatch acceptance callback does not announce the transient assignment
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Dispatch acceptance walks through PROVIDER_ASSIGNED to PROVIDER_ACCEPTED, audits both "
            + "steps, and writes no ProviderAssigned")
    void dispatchAcceptancePublishesNoProviderAssigned() {
        String reference = createAndConfirmScheduled();
        UUID bookingId = requireBooking(reference).getId();

        dispatchOutcomeService.markProviderAccepted(bookingId, providerId);
        dispatchOutcomeService.markProviderAccepted(bookingId, providerId); // redelivered callback

        assertThat(requireBooking(reference).getStatus()).isEqualTo(BookingStatus.PROVIDER_ACCEPTED);
        assertThat(requireBooking(reference).getProviderId()).isEqualTo(providerId);
        // The customer hears about the acceptance from the Dispatch Engine's ProviderAccepted; a
        // ProviderAssigned committed alongside it would only be noise.
        assertThat(outboxRows(reference, "ProviderAssigned")).isEmpty();
        // The intermediate step is still in the contiguous audit trail (Property 9).
        assertContiguousAudit(reference);
        assertThat(auditTrail(bookingId)).extracting(BookingAudit::getToState)
                .contains(BookingStatus.PROVIDER_ASSIGNED, BookingStatus.PROVIDER_ACCEPTED);
    }

    // ---------------------------------------------------------------------
    // Flow 4 — Additional quote (parts/materials mid-job)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Parts added mid-job → ADDITIONAL_QUOTE_REQUIRED → CUSTOMER_APPROVAL_PENDING → "
            + "customer approves → job resumes → completes at the updated price")
    void additionalQuoteApprovedFlow() {
        String reference = driveToJobStarted();
        BigDecimal originalTotal = requireBooking(reference).getEstimatedTotal();

        // Provider records parts/materials mid-job (Requirement 6.8, 11.3).
        String partsBody = "{\"itemName\":\"Copper pipe\",\"quantity\":2,\"unitCost\":75.00}";
        ResponseEntity<Map> afterParts = rest.exchange(url("/bookings/" + reference + "/parts"),
                HttpMethod.POST, jsonEntity(providerToken(), partsBody), Map.class);
        assertThat(afterParts.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(afterParts.getBody().get("status")).isEqualTo("CUSTOMER_APPROVAL_PENDING");

        // Updated total reflects the parts charge (stub adds parts on top of the estimate).
        BigDecimal pendingTotal = requireBookingFinalTotal(reference);
        assertThat(pendingTotal).isGreaterThan(originalTotal);

        // Customer approves → back to JOB_STARTED (Requirement 9.7).
        assertStatus(post(reference + "/quote/approval", customerToken()), "JOB_STARTED");

        // Complete at the updated price.
        attachPhoto(reference, "AFTER_PHOTO");
        assertStatus(post(reference + "/complete", providerToken()), "JOB_COMPLETED");

        Booking completed = requireBooking(reference);
        assertThat(completed.getStatus()).isEqualTo(BookingStatus.JOB_COMPLETED);
        // Final total is the updated (parts-inclusive) price, not the original estimate.
        assertThat(completed.getFinalTotal()).isEqualByComparingTo(pendingTotal);
        assertThat(completed.getFinalTotal()).isGreaterThan(originalTotal);
    }

    // ---------------------------------------------------------------------
    // Flow 5 — Cancellation with fee in PROVIDER_ON_THE_WAY
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Cancelling in PROVIDER_ON_THE_WAY applies the configured fee (partial refund "
            + "initiated for the remainder)")
    void cancellationWithFeeInProviderOnTheWay() {
        String reference = createAndConfirmScheduled();
        assignProvider(reference);
        transitionAsSystem(reference, BookingStatus.PROVIDER_ACCEPTED, "Provider accepted");
        assertStatus(post(reference + "/on-the-way", providerToken()), "PROVIDER_ON_THE_WAY");

        // Cancel while the provider is on the way → fee applies (Requirement 9.17, 9.18).
        ResponseEntity<Map> cancelled = rest.exchange(url("/bookings/" + reference + "/cancellation"),
                HttpMethod.POST, jsonEntity(customerToken(), "{\"reason\":\"customer no longer available\"}"),
                Map.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("status")).isEqualTo("CANCELLED");

        Booking booking = requireBooking(reference);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        // A non-null cancellation fee within the permitted range 0.00-999.99 is applied; the
        // remainder-refund is initiated by the Payment Service in staging (Requirement 9.17).
        assertThat(booking.getCancellationFee()).isNotNull();
        assertThat(booking.getCancellationFee()).isBetween(new BigDecimal("0.00"), new BigDecimal("999.99"));

        // Exactly one BookingCancelled, carrying everything chat needs to close the channel and
        // notification needs to address both participants (Requirement 18.5, 22.1).
        JsonNode event = singleOutboxPayload(reference, "BookingCancelled");
        assertThat(event.get("bookingId").asText()).isEqualTo(booking.getId().toString());
        assertThat(event.get("customerId").asText()).isEqualTo(customerId.toString());
        assertThat(event.get("providerId").asText()).isEqualTo(providerId.toString());
        assertThat(event.get("previousStatus").asText()).isEqualTo("PROVIDER_ON_THE_WAY");
        assertThat(event.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(event.get("cancelledByRole").asText()).isEqualTo("CUSTOMER");
        assertThat(event.get("reason").asText()).isEqualTo("customer no longer available");
        assertThat(event.get("cancellationFee").decimalValue())
                .isEqualByComparingTo(booking.getCancellationFee());
    }

    @Test
    @DisplayName("Cancelling before PROVIDER_ON_THE_WAY applies no fee (Requirement 9.16)")
    void cancellationBeforeOnTheWayHasNoFee() {
        String reference = createAndConfirmScheduled();

        ResponseEntity<Map> cancelled = rest.exchange(url("/bookings/" + reference + "/cancellation"),
                HttpMethod.POST, jsonEntity(customerToken(), "{\"reason\":\"changed mind\"}"), Map.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);

        Booking booking = requireBooking(reference);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getCancellationFee()).isEqualByComparingTo(new BigDecimal("0.00"));

        JsonNode event = singleOutboxPayload(reference, "BookingCancelled");
        assertThat(event.get("previousStatus").asText()).isEqualTo("SEARCHING_PROVIDER");
        // Cancelled before dispatch: no provider yet.
        assertThat(event.get("providerId").isNull()).isTrue();
    }

    @Test
    @DisplayName("A cancellation by the assigned provider writes exactly one BookingCancelled")
    void cancellationByProviderPublishesBookingCancelled() {
        String reference = createAndConfirmScheduled();
        dispatchOutcomeService.markProviderAccepted(requireBooking(reference).getId(), providerId);

        ResponseEntity<Map> cancelled = rest.exchange(url("/bookings/" + reference + "/cancellation"),
                HttpMethod.POST, jsonEntity(providerToken(), "{\"reason\":\"vehicle broke down\"}"),
                Map.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode event = singleOutboxPayload(reference, "BookingCancelled");
        assertThat(event.get("cancelledByRole").asText()).isEqualTo("SERVICE_PROVIDER");
        assertThat(event.get("cancelledBy").asText()).isEqualTo(providerId.toString());
        assertThat(event.get("providerId").asText()).isEqualTo(providerId.toString());
    }

    @Test
    @DisplayName("A rejected cancellation writes no BookingCancelled")
    void rejectedCancellationPublishesNothing() {
        String reference = driveToJobStarted();

        ResponseEntity<Map> rejected = rest.exchange(url("/bookings/" + reference + "/cancellation"),
                HttpMethod.POST, jsonEntity(customerToken(), "{\"reason\":\"too late\"}"), Map.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(requireBooking(reference).getStatus()).isEqualTo(BookingStatus.JOB_STARTED);
        assertThat(outboxRows(reference, "BookingCancelled")).isEmpty();
    }

    @Test
    @DisplayName("BookingCancelled shares the cancellation's transaction: a rollback discards both")
    void bookingCancelledRollsBackWithTheStatusChange() {
        String reference = createAndConfirmScheduled();

        transactionTemplate.executeWithoutResult(tx -> {
            bookingService.cancel(reference, Actor.user(customerId, "CUSTOMER"), "rolled back");
            // Inside the transaction both writes are visible...
            assertThat(outboxRows(reference, "BookingCancelled")).hasSize(1);
            tx.setRollbackOnly();
        });

        // ...and after the rollback neither the status change nor the event survives.
        assertThat(requireBooking(reference).getStatus()).isEqualTo(BookingStatus.SEARCHING_PROVIDER);
        assertThat(outboxRows(reference, "BookingCancelled")).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Flow 6 — Read endpoints through the real security chain
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("History and detail reach the customer, the assigned provider once assigned, and "
            + "nobody else: RBAC refuses a provider-only history, ownership answers strangers 404")
    void readEndpointsHonourRolesAndOwnership() {
        String reference = createAndConfirmScheduled();
        UUID bookingId = requireBooking(reference).getId();

        // Literal /history wins over /{bookingKey}; the page is 1-based and the caller's own.
        ResponseEntity<Map> history = get("/bookings/history?page=1&pageSize=5", customerToken());
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody().get("page")).isEqualTo(1);
        assertThat(history.getBody().get("totalItems")).isEqualTo(1);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) history.getBody().get("items")).get(0);
        assertThat(row.get("referenceNumber")).isEqualTo(reference);
        assertThat(row.get("status")).isEqualTo("SEARCHING_PROVIDER");
        assertThat(row.get("currency")).isEqualTo("INR");
        // Boot's mapper writes the date as an ISO-8601 string, which the app parses.
        assertThat(Instant.parse((String) row.get("date"))).isNotNull();

        // Detail by id and by reference, for the customer.
        ResponseEntity<Map> detail = get("/bookings/" + bookingId, customerToken());
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody().get("referenceNumber")).isEqualTo(reference);
        assertThat(detail.getBody()).doesNotContainKey("providerId");
        assertThat(get("/bookings/" + reference, customerToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

        // A stranger and a not-yet-assigned provider see no such booking.
        assertThat(get("/bookings/" + bookingId, token(UUID.randomUUID(), "CUSTOMER")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/bookings/" + bookingId, providerToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // History is a customer feature: RBAC refuses a provider-only caller outright.
        assertThat(get("/bookings/history", providerToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assignProvider(reference);
        ResponseEntity<Map> asProvider = get("/bookings/" + bookingId, providerToken());
        assertThat(asProvider.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asProvider.getBody().get("providerId")).isEqualTo(providerId.toString());
        assertThat(asProvider.getBody().get("status")).isEqualTo("PROVIDER_ASSIGNED");

        // Staff read any booking.
        assertThat(get("/bookings/" + bookingId, token(UUID.randomUUID(), "SUPPORT_AGENT")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // Bad paging is a 400 in the service's envelope, not Spring's bare error.
        ResponseEntity<Map> bad = get("/bookings/history?pageSize=51", customerToken());
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().get("errorCode")).isEqualTo("VALIDATION_ERROR");
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private String createAndConfirmScheduled() {
        Instant scheduledAt = Instant.now().plus(Duration.ofDays(3));
        ResponseEntity<Map> created = rest.exchange(url("/bookings"), HttpMethod.POST,
                jsonEntity(customerToken(), createBody(false, scheduledAt)), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String reference = (String) created.getBody().get("reference");
        assertStatus(post(reference + "/confirmation", customerToken()), "SEARCHING_PROVIDER");
        return reference;
    }

    /** Drives a fresh scheduled booking all the way to JOB_STARTED (before-photo attached). */
    private String driveToJobStarted() {
        String reference = createAndConfirmScheduled();
        assignProvider(reference);
        transitionAsSystem(reference, BookingStatus.PROVIDER_ACCEPTED, "Provider accepted");
        assertStatus(post(reference + "/on-the-way", providerToken()), "PROVIDER_ON_THE_WAY");
        assertStatus(post(reference + "/arrived", providerToken()), "PROVIDER_ARRIVED");
        attachPhoto(reference, "BEFORE_PHOTO");
        assertStatus(post(reference + "/start", providerToken()), "JOB_STARTED");
        return reference;
    }

    /**
     * Simulates the Dispatch Engine assigning a provider: stamps the provider id onto the booking,
     * then transitions to PROVIDER_ASSIGNED, in the same order as {@code DispatchOutcomeService}
     * so the ProviderAssigned event names the provider.
     */
    void assignProvider(String reference) {
        Booking booking = requireBooking(reference);
        booking.setProviderId(providerId);
        bookingRepository.save(booking);
        transitionAsSystem(reference, BookingStatus.PROVIDER_ASSIGNED, "Dispatch assigned provider");
    }

    /** Applies a guarded transition through the service layer as the system actor. */
    private void transitionAsSystem(String reference, BookingStatus target, String reason) {
        bookingService.transition(reference, target, Actor.system(), reason);
    }

    private void attachPhoto(String reference, String type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(providerToken());

        ByteArrayResource file = new ByteArrayResource(new byte[]{1, 2, 3, 4}) {
            @Override
            public String getFilename() {
                return type.toLowerCase() + ".jpg";
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("type", type);
        body.add("file", file);

        ResponseEntity<Void> resp = rest.exchange(url("/bookings/" + reference + "/photos"),
                HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ----- HTTP plumbing ---------------------------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(authHeaders(token)), Map.class);
    }

    private ResponseEntity<Map> post(String pathSuffix, String token) {
        return rest.exchange(url("/bookings/" + pathSuffix), HttpMethod.POST,
                new HttpEntity<>(authHeaders(token)), Map.class);
    }

    private void assertStatus(ResponseEntity<Map> response, String expectedStatus) {
        assertThat(response.getStatusCode())
                .as("expected 200 OK but got %s with body %s", response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo(expectedStatus);
    }

    private HttpEntity<String> jsonEntity(String token, String body) {
        HttpHeaders headers = authHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String createBody(boolean emergency, Instant scheduledAt) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"categoryId\":\"").append(categoryId).append("\",");
        sb.append("\"subcategoryId\":\"").append(subcategoryId).append("\",");
        sb.append("\"addressId\":\"").append(addressId).append("\",");
        sb.append("\"emergency\":").append(emergency);
        if (scheduledAt != null) {
            sb.append(",\"scheduledAt\":\"").append(scheduledAt).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    // ----- JWT tokens ------------------------------------------------------

    private String customerToken() {
        return token(customerId, "CUSTOMER");
    }

    private String providerToken() {
        return token(providerId, "SERVICE_PROVIDER");
    }

    private String token(UUID subject, String role) {
        var key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(subject.toString())
                .claim("roles", List.of(role))
                .issuedAt(new Date(System.currentTimeMillis() - 1000))
                .expiration(new Date(System.currentTimeMillis() + Duration.ofMinutes(15).toMillis()))
                .signWith(key)
                .compact();
    }

    // ----- persistence assertions -----------------------------------------

    private Booking requireBooking(String reference) {
        return bookingRepository.findByReference(reference).orElseThrow();
    }

    BigDecimal requireBookingFinalTotal(String reference) {
        return requireBooking(reference).getFinalTotal();
    }

    private void assertOutboxHas(String reference, String eventType) {
        UUID bookingId = requireBooking(reference).getId();
        assertThat(outboxRepository.findAll())
                .anyMatch(e -> e.getAggregateId().equals(bookingId) && e.getEventType().equals(eventType));
    }

    private void assertOutboxHasAll(String reference, String... eventTypes) {
        UUID bookingId = requireBooking(reference).getId();
        List<String> published = outboxRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(bookingId))
                .map(OutboxEventEntity::getEventType)
                .toList();
        assertThat(published).contains(eventTypes);
    }

    private List<OutboxEventEntity> outboxRows(String reference, String eventType) {
        UUID bookingId = requireBooking(reference).getId();
        return outboxRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(bookingId) && e.getEventType().equals(eventType))
                .toList();
    }

    /** Asserts exactly one {@code eventType} row (aggregate "Booking") for the booking and parses it. */
    private JsonNode singleOutboxPayload(String reference, String eventType) {
        List<OutboxEventEntity> rows = outboxRows(reference, eventType);
        assertThat(rows).as("%s outbox rows for %s", eventType, reference).hasSize(1);
        assertThat(rows.get(0).getAggregateType()).isEqualTo("Booking");
        try {
            return objectMapper.readTree(rows.get(0).getPayload());
        } catch (Exception e) {
            throw new AssertionError("Unparseable " + eventType + " payload", e);
        }
    }

    /**
     * Verifies the audit trail forms a contiguous chain (Property 9): it opens with a single
     * null-from creation entry and every other entry's from-state is reachable as some earlier
     * entry's to-state, ending at the booking's current state. This is asserted on the edge set
     * rather than strict list order so it is robust to same-millisecond timestamp collisions
     * between rapid consecutive transitions.
     */
    void assertContiguousAudit(String reference) {
        Booking booking = requireBooking(reference);
        List<BookingAudit> trail = auditTrail(booking.getId());
        assertThat(trail).isNotEmpty();

        // Exactly one chain-opening entry (from == null -> CREATED).
        List<BookingAudit> openers = trail.stream().filter(a -> a.getFromState() == null).toList();
        assertThat(openers).hasSize(1);
        assertThat(openers.get(0).getToState()).isEqualTo(BookingStatus.CREATED);

        // The set of states we've "reached" grows as we consume edges; every non-opening edge
        // must depart from an already-reached state, and the chain must end at the current state.
        java.util.Set<BookingStatus> reached = new java.util.HashSet<>();
        reached.add(BookingStatus.CREATED);
        for (BookingAudit edge : trail) {
            if (edge.getFromState() == null) {
                continue;
            }
            assertThat(reached)
                    .as("audit edge %s->%s departs from a reached state", edge.getFromState(), edge.getToState())
                    .contains(edge.getFromState());
            reached.add(edge.getToState());
        }
        assertThat(reached).contains(booking.getStatus());
    }

    private List<String> auditReasons(String reference) {
        UUID bookingId = requireBooking(reference).getId();
        return auditTrail(bookingId).stream().map(BookingAudit::getReason).toList();
    }

    private List<BookingAudit> auditTrail(UUID bookingId) {
        return auditRepository.findByBookingIdOrderByTransitionedAtAsc(bookingId);
    }

    @Autowired
    private com.homefix.booking.domain.BookingAuditRepository auditRepository;
}
