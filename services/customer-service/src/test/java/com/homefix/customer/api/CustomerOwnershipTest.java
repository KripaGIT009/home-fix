package com.homefix.customer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.homefix.customer.domain.CustomerProfile;
import com.homefix.customer.domain.DeletionRequest;
import com.homefix.customer.service.AddressResult;
import com.homefix.customer.service.CustomerProfileService;
import com.homefix.customer.service.CustomerProfileService.DetectedLocation;

/**
 * Ownership tests for the customer-scoped endpoints: the RBAC filter only decides whether a role may
 * use an endpoint at all, so without a per-request owner check any authenticated {@code CUSTOMER}
 * could act on another customer's {@code {id}}. These tests assert caller A is rejected with 403 for
 * caller B's id, succeeds for their own, and that a staff ({@code ADMIN}) caller succeeds for
 * anybody's id.
 */
class CustomerOwnershipTest {

    private static final UUID CALLER_A = UUID.randomUUID();
    private static final UUID CALLER_B = UUID.randomUUID();

    private CustomerProfileService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CustomerProfileService.class);
        CallerIdentity callerIdentity = new CallerIdentity();
        mvc = MockMvcBuilders
                .standaloneSetup(new CustomerController(service, callerIdentity),
                        new AddressController(service, callerIdentity))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(UUID callerId, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                callerId.toString(), "n/a", AuthorityUtils.createAuthorityList(roles)));
    }

    /** {@code PUT /customers/{id}/profile} consumes multipart, so the JSON travels as a part. */
    private RequestBuilder profileUpdate(UUID customerId) {
        MockMultipartFile profilePart = new MockMultipartFile("profile", "profile.json",
                MediaType.APPLICATION_JSON_VALUE,
                """
                {"displayName":"Sherlock Holmes","email":"sherlock@example.com",
                 "photoUrl":"https://cdn/photo.png"}
                """.getBytes(StandardCharsets.UTF_8));
        MockMultipartHttpServletRequestBuilder builder =
                multipart("/customers/{id}/profile", customerId);
        builder.file(profilePart);
        return builder.with(request -> {
            request.setMethod("PUT");
            return request;
        });
    }

    private static String addressBody() {
        return "{\"label\":\"Home\",\"lat\":51.5237,\"lng\":-0.1585}";
    }

    private static String locationBody() {
        return "{\"lat\":51.5237,\"lng\":-0.1585,\"gpsDenied\":false}";
    }

    // ----- PUT /customers/{id}/profile -----

    @Test
    void updateProfile_forAnotherCustomer_isForbidden() throws Exception {
        authenticate(CALLER_A, "ROLE_CUSTOMER");

        mvc.perform(profileUpdate(CALLER_B))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        verifyNoInteractions(service);
    }

    @Test
    void updateProfile_forOwnId_succeeds() throws Exception {
        authenticate(CALLER_A, "ROLE_CUSTOMER");
        when(service.updateProfile(eq(CALLER_A), anyString(), anyString(), any()))
                .thenReturn(CustomerProfile.forUser(CALLER_A));

        mvc.perform(profileUpdate(CALLER_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(CALLER_A.toString()));

        verify(service).updateProfile(eq(CALLER_A), anyString(), anyString(), any());
    }

    @Test
    void updateProfile_asAdmin_forAnotherCustomer_succeeds() throws Exception {
        authenticate(CALLER_A, "ROLE_ADMIN");
        when(service.updateProfile(eq(CALLER_B), anyString(), anyString(), any()))
                .thenReturn(CustomerProfile.forUser(CALLER_B));

        mvc.perform(profileUpdate(CALLER_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(CALLER_B.toString()));

        verify(service).updateProfile(eq(CALLER_B), anyString(), anyString(), any());
    }

    // ----- POST /customers/{id}/addresses -----

    @Test
    void addAddress_forAnotherCustomer_isForbidden() throws Exception {
        authenticate(CALLER_A, "ROLE_CUSTOMER");

        mvc.perform(post("/customers/{id}/addresses", CALLER_B)
                        .contentType(MediaType.APPLICATION_JSON).content(addressBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        verify(service, never()).addAddress(any(), any(), anyDouble(), anyDouble());
    }

    @Test
    void addAddress_forOwnId_succeeds() throws Exception {
        authenticate(CALLER_A, "ROLE_CUSTOMER");
        when(service.addAddress(eq(CALLER_A), any(), anyDouble(), anyDouble()))
                .thenReturn(new AddressResult(UUID.randomUUID(), 51.5237, -0.1585, true, true, null));

        mvc.perform(post("/customers/{id}/addresses", CALLER_A)
                        .contentType(MediaType.APPLICATION_JSON).content(addressBody()))
                .andExpect(status().isCreated());

        verify(service).addAddress(eq(CALLER_A), any(), anyDouble(), anyDouble());
    }

    @Test
    void addAddress_asAdmin_forAnotherCustomer_succeeds() throws Exception {
        authenticate(CALLER_A, "ROLE_ADMIN");
        when(service.addAddress(eq(CALLER_B), any(), anyDouble(), anyDouble()))
                .thenReturn(new AddressResult(UUID.randomUUID(), 51.5237, -0.1585, true, true, null));

        mvc.perform(post("/customers/{id}/addresses", CALLER_B)
                        .contentType(MediaType.APPLICATION_JSON).content(addressBody()))
                .andExpect(status().isCreated());

        verify(service).addAddress(eq(CALLER_B), any(), anyDouble(), anyDouble());
    }

    // ----- DELETE /customers/{id}/addresses/{addressId} -----

    @Test
    void deleteAddress_forAnotherCustomer_isForbidden() throws Exception {
        authenticate(CALLER_A, "ROLE_CUSTOMER");

        mvc.perform(delete("/customers/{id}/addresses/{addressId}", CALLER_B, UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        verify(service, never()).deleteAddress(any(), any());
    }

    @Test
    void deleteAddress_forOwnId_succeeds() throws Exception {
        authenticate(CALLER_A, "ROLE_CUSTOMER");
        UUID addressId = UUID.randomUUID();

        mvc.perform(delete("/customers/{id}/addresses/{addressId}", CALLER_A, addressId))
                .andExpect(status().isNoContent());

        verify(service).deleteAddress(CALLER_A, addressId);
    }

    @Test
    void deleteAddress_asAdmin_forAnotherCustomer_succeeds() throws Exception {
        authenticate(CALLER_A, "ROLE_ADMIN");
        UUID addressId = UUID.randomUUID();

        mvc.perform(delete("/customers/{id}/addresses/{addressId}", CALLER_B, addressId))
                .andExpect(status().isNoContent());

        verify(service).deleteAddress(CALLER_B, addressId);
    }

    // ----- POST /customers/{id}/location/detect -----

    @Test
    void detectLocation_forAnotherCustomer_isForbidden() throws Exception {
        authenticate(CALLER_A, "ROLE_CUSTOMER");

        mvc.perform(post("/customers/{id}/location/detect", CALLER_B)
                        .contentType(MediaType.APPLICATION_JSON).content(locationBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        verifyNoInteractions(service);
    }

    @Test
    void detectLocation_forOwnId_succeeds() throws Exception {
        authenticate(CALLER_A, "ROLE_CUSTOMER");
        when(service.detectLocation(any(), any(), anyBoolean()))
                .thenReturn(new DetectedLocation(51.5237, -0.1585, "221B Baker Street", true));

        mvc.perform(post("/customers/{id}/location/detect", CALLER_A)
                        .contentType(MediaType.APPLICATION_JSON).content(locationBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.geocoded").value(true));
    }

    @Test
    void detectLocation_asAdmin_forAnotherCustomer_succeeds() throws Exception {
        authenticate(CALLER_A, "ROLE_ADMIN");
        when(service.detectLocation(any(), any(), anyBoolean()))
                .thenReturn(new DetectedLocation(51.5237, -0.1585, "221B Baker Street", true));

        mvc.perform(post("/customers/{id}/location/detect", CALLER_B)
                        .contentType(MediaType.APPLICATION_JSON).content(locationBody()))
                .andExpect(status().isOk());
    }

    // ----- POST /customers/{id}/deletion -----

    @Test
    void requestDeletion_forAnotherCustomer_isForbidden() throws Exception {
        authenticate(CALLER_A, "ROLE_CUSTOMER");

        mvc.perform(post("/customers/{id}/deletion", CALLER_B))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        verify(service, never()).requestDeletion(any());
    }

    @Test
    void requestDeletion_forOwnId_succeeds() throws Exception {
        authenticate(CALLER_A, "ROLE_CUSTOMER");
        when(service.requestDeletion(CALLER_A)).thenReturn(deletionRequest(CALLER_A));

        mvc.perform(post("/customers/{id}/deletion", CALLER_A))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));

        verify(service).requestDeletion(CALLER_A);
    }

    @Test
    void requestDeletion_asAdmin_forAnotherCustomer_succeeds() throws Exception {
        authenticate(CALLER_A, "ROLE_ADMIN");
        when(service.requestDeletion(CALLER_B)).thenReturn(deletionRequest(CALLER_B));

        mvc.perform(post("/customers/{id}/deletion", CALLER_B))
                .andExpect(status().isAccepted());

        verify(service).requestDeletion(CALLER_B);
    }

    // ----- Unauthenticated -----

    @Test
    void unauthenticatedCaller_isRejected() throws Exception {
        mvc.perform(post("/customers/{id}/deletion", CALLER_A))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));

        verifyNoInteractions(service);
    }

    private static DeletionRequest deletionRequest(UUID customerId) {
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        return DeletionRequest.acknowledge(customerId, now, now.plusSeconds(30 * 86400L));
    }
}
