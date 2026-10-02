package com.homefix.dispatch.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.homefix.dispatch.config.DispatchRbacConfig;
import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.OfferStatus;
import com.homefix.dispatch.service.JobOfferService;
import com.homefix.dispatch.service.fake.InMemoryJobOfferStore;
import com.homefix.dispatch.service.fake.MutableClock;
import com.homefix.shared.security.RbacEnforcementFilter;
import com.homefix.shared.security.RbacProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The provider-facing offer API end to end below the gateway (Requirements 8.5-8.7, 28.8): the
 * real {@link RbacEnforcementFilter} over the rules {@link DispatchRbacConfig} registers, the real
 * controller, {@link CallerIdentity} and {@link JobOfferService}, and an in-memory store on a
 * hand-moved clock. Covers the SERVICE_PROVIDER role gate, per-offer ownership (someone else's
 * offer is indistinguishable from none), the response shape, and every error code.
 */
class JobOfferControllerTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");

    private MutableClock clock;
    private JobOfferService offers;
    private MockMvc mockMvc;

    private final UUID provider = UUID.randomUUID();
    private final UUID otherProvider = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        offers = new JobOfferService(new InMemoryJobOfferStore(), clock);
        RbacProperties rbac = new RbacProperties();
        new DispatchRbacConfig(rbac).registerEndpointRoles();
        JobOfferController controller = new JobOfferController(offers, new CallerIdentity(), clock);
        // Serialise as Spring Boot does: ISO-8601 instants, not epoch numbers.
        var mapper = JsonMapper.builder().findAndAddModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new RbacEnforcementFilter(rbac))
                .build();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String principal, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, "n/a", AuthorityUtils.createAuthorityList("ROLE_" + role)));
    }

    private void actAsProvider(UUID id) {
        authenticate(id.toString(), "SERVICE_PROVIDER");
    }

    private void offerTo(UUID booking, UUID to) {
        DispatchRequest request = new DispatchRequest(booking, UUID.randomUUID(), 12.9, 77.6,
                UUID.fromString("00000000-0000-0000-0000-0000000000aa"), List.of("plumbing"), true,
                T0, "HFX-2026-0000123", Instant.parse("2026-10-02T14:30:00Z"));
        offers.open(request, to, WINDOW);
    }

    // ---- role gate ------------------------------------------------------------------------------

    @Test
    void customerIsForbiddenFromEveryOfferEndpoint() throws Exception {
        offerTo(bookingId, provider);
        authenticate(provider.toString(), "CUSTOMER");

        mockMvc.perform(get("/dispatch/offers")).andExpect(status().isForbidden());
        mockMvc.perform(get("/dispatch/offers/" + bookingId)).andExpect(status().isForbidden());
        mockMvc.perform(post("/dispatch/offers/" + bookingId + "/accept")).andExpect(status().isForbidden());
        mockMvc.perform(post("/dispatch/offers/" + bookingId + "/decline")).andExpect(status().isForbidden());

        assertThat(offers.statusOf(bookingId, provider)).contains(OfferStatus.PENDING);
    }

    @Test
    void staffHaveNoReadPathToOffers() throws Exception {
        authenticate(UUID.randomUUID().toString(), "ADMIN");

        mockMvc.perform(get("/dispatch/offers")).andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedCallerIsUnauthorized() throws Exception {
        mockMvc.perform(get("/dispatch/offers")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/dispatch/offers/" + bookingId + "/accept"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void principalThatIsNotAUserId_isUnauthorized() throws Exception {
        authenticate("not-a-uuid", "SERVICE_PROVIDER");

        mockMvc.perform(get("/dispatch/offers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PRINCIPAL"));
    }

    // ---- reads ---------------------------------------------------------------------------------

    @Test
    void list_returnsTheCallersOpenOffersInTheDocumentedShape() throws Exception {
        offerTo(bookingId, provider);
        offerTo(UUID.randomUUID(), otherProvider);
        clock.advance(Duration.ofSeconds(15));
        actAsProvider(provider);

        mockMvc.perform(get("/dispatch/offers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].reference").value("HFX-2026-0000123"))
                .andExpect(jsonPath("$[0].emergency").value(true))
                .andExpect(jsonPath("$[0].subcategoryId").value("00000000-0000-0000-0000-0000000000aa"))
                .andExpect(jsonPath("$[0].scheduledAt").value("2026-10-02T14:30:00Z"))
                .andExpect(jsonPath("$[0].offeredAt").value("2026-10-02T10:00:00Z"))
                .andExpect(jsonPath("$[0].expiresAt").value("2026-10-02T10:01:00Z"))
                .andExpect(jsonPath("$[0].expiresInSeconds").value(45))
                .andExpect(jsonPath("$[0].timeoutSeconds").value(60));
    }

    @Test
    void list_isEmptyForAProviderWithNoOffers() throws Exception {
        offerTo(bookingId, provider);
        actAsProvider(otherProvider);

        mockMvc.perform(get("/dispatch/offers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void detail_isVisibleToTheOfferedProvider() throws Exception {
        offerTo(bookingId, provider);
        actAsProvider(provider);

        mockMvc.perform(get("/dispatch/offers/" + bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.expiresInSeconds").value(60));
    }

    @Test
    void detail_ofSomeoneElsesOffer_isTheSame404AsNoOffer() throws Exception {
        offerTo(bookingId, provider);
        actAsProvider(otherProvider);

        mockMvc.perform(get("/dispatch/offers/" + bookingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("OFFER_NOT_FOUND"));
        mockMvc.perform(get("/dispatch/offers/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("OFFER_NOT_FOUND"));
    }

    @Test
    void detail_ofAnExpiredOffer_showsItsStatusWithNoTimeLeft() throws Exception {
        offerTo(bookingId, provider);
        clock.advance(WINDOW);
        offers.expire(bookingId, provider);
        actAsProvider(provider);

        mockMvc.perform(get("/dispatch/offers/" + bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.expiresInSeconds").value(0));
    }

    // ---- decisions -----------------------------------------------------------------------------

    @Test
    void accept_recordsTheDecision() throws Exception {
        offerTo(bookingId, provider);
        actAsProvider(provider);

        mockMvc.perform(post("/dispatch/offers/" + bookingId + "/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.expiresInSeconds").value(0));

        assertThat(offers.statusOf(bookingId, provider)).contains(OfferStatus.ACCEPTED);
    }

    @Test
    void decline_recordsTheDecision() throws Exception {
        offerTo(bookingId, provider);
        actAsProvider(provider);

        mockMvc.perform(post("/dispatch/offers/" + bookingId + "/decline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));

        assertThat(offers.statusOf(bookingId, provider)).contains(OfferStatus.DECLINED);
    }

    @Test
    void accept_ofSomeoneElsesOffer_is404AndChangesNothing() throws Exception {
        offerTo(bookingId, provider);
        actAsProvider(otherProvider);

        mockMvc.perform(post("/dispatch/offers/" + bookingId + "/accept"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("OFFER_NOT_FOUND"));

        assertThat(offers.statusOf(bookingId, provider)).contains(OfferStatus.PENDING);
    }

    @Test
    void lateAccept_isConflictOfferExpired() throws Exception {
        offerTo(bookingId, provider);
        clock.advance(WINDOW.plusSeconds(1));
        actAsProvider(provider);

        mockMvc.perform(post("/dispatch/offers/" + bookingId + "/accept"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OFFER_EXPIRED"));

        assertThat(offers.statusOf(bookingId, provider)).contains(OfferStatus.EXPIRED);
    }

    @Test
    void secondDecision_isConflictAlreadyDecided() throws Exception {
        offerTo(bookingId, provider);
        actAsProvider(provider);
        mockMvc.perform(post("/dispatch/offers/" + bookingId + "/decline")).andExpect(status().isOk());

        mockMvc.perform(post("/dispatch/offers/" + bookingId + "/accept"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OFFER_ALREADY_DECIDED"))
                .andExpect(jsonPath("$.message").value("This job offer is already declined"));
    }
}
