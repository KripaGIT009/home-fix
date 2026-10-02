package com.homefix.customer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.homefix.customer.config.InternalApiKeyFilter;
import com.homefix.customer.config.WebSecurityConfig;
import com.homefix.customer.domain.Address;
import com.homefix.customer.domain.AddressRepository;
import com.homefix.shared.security.HomefixSecurityAutoConfiguration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Web-layer tests for {@code GET /internal/addresses/{addressId}} through the service's real
 * security chain ({@link WebSecurityConfig} plus the shared JWT and RBAC filters), so the
 * service-credential guard is exercised exactly as deployed: no key, a wrong key, and an end-user
 * token (even the address owner's) are all refused; only the shared {@code X-Internal-Api-Key}
 * reaches the handler.
 */
@WebMvcTest(controllers = InternalAddressController.class, properties = {
        "homefix.security.jwt-secret=" + InternalAddressControllerTest.JWT_SECRET,
        "homefix.customer.internal-api-key=" + InternalAddressControllerTest.INTERNAL_KEY
})
@Import(WebSecurityConfig.class)
@ImportAutoConfiguration(HomefixSecurityAutoConfiguration.class)
class InternalAddressControllerTest {

    static final String JWT_SECRET = "unit-test-signing-secret-that-is-32b+";
    static final String INTERNAL_KEY = "test-internal-key";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AddressRepository addressRepository;

    @Test
    void validServiceCredential_returnsOwnerCoordinatesAndLabel() throws Exception {
        UUID customerId = UUID.randomUUID();
        Address address = Address.create(customerId, "Home", 25.5941, 85.1376,
                "ciphertext-of-the-street-address", true);
        when(addressRepository.findById(address.getId())).thenReturn(Optional.of(address));

        mockMvc.perform(get("/internal/addresses/{id}", address.getId())
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressId").value(address.getId().toString()))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.lat").value(25.5941))
                .andExpect(jsonPath("$.lng").value(85.1376))
                // The label is what the assigned provider is shown to find the job.
                .andExpect(jsonPath("$.label").value("Home"))
                // The encrypted street address stays out of the projection.
                .andExpect(jsonPath("$.addressTextEncrypted").doesNotExist());
    }

    @Test
    void unknownAddress_returns404InSharedEnvelope() throws Exception {
        when(addressRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/addresses/{id}", UUID.randomUUID())
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ADDRESS_NOT_FOUND"));
    }

    @Test
    void missingServiceCredential_returns401AndNeverTouchesAddresses() throws Exception {
        mockMvc.perform(get("/internal/addresses/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_AUTH_FAILED"));

        verify(addressRepository, never()).findById(any());
    }

    @Test
    void wrongServiceCredential_returns401AndNeverTouchesAddresses() throws Exception {
        mockMvc.perform(get("/internal/addresses/{id}", UUID.randomUUID())
                        .header(InternalApiKeyFilter.HEADER, "not-the-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_AUTH_FAILED"));

        verify(addressRepository, never()).findById(any());
    }

    @Test
    void endUserTokenWithoutServiceCredential_isRefused() throws Exception {
        // A signed-in user, even staff, must not be able to read where a customer lives through
        // the internal surface.
        String userToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of("SUPER_ADMIN", "CUSTOMER"))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(get("/internal/addresses/{id}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isUnauthorized());

        verify(addressRepository, never()).findById(any());
    }

    @Test
    void filterWithoutConfiguredKey_refusesEveryInternalRequest() throws Exception {
        // Fail closed: an unset key must not mean "no key required".
        InternalApiKeyFilter unconfigured = new InternalApiKeyFilter("  ");
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/internal/addresses/" + UUID.randomUUID());
        request.addHeader(InternalApiKeyFilter.HEADER, "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        unconfigured.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }
}
