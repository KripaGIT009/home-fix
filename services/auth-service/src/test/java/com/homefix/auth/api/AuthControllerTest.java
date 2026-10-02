package com.homefix.auth.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.homefix.auth.registration.RegistrationException;
import com.homefix.auth.registration.RegistrationService;
import com.homefix.auth.registration.RegistrationService.VerificationResult;
import com.homefix.auth.token.TokenPair;
import com.homefix.auth.token.IntrospectionService;

/**
 * Web-layer tests for the auth controllers using standalone MockMvc (no Redis/DB/context).
 * Verifies request validation, status codes, the shared error envelope, and introspection.
 */
class AuthControllerTest {

    private RegistrationService registrationService;
    private IntrospectionService introspectionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registrationService = mock(RegistrationService.class);
        introspectionService = mock(IntrospectionService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new AuthController(registrationService),
                        new IntrospectController(introspectionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void requestOtp_valid_returns202() throws Exception {
        when(registrationService.requestOtp(eq("+919876543210"), eq("CUSTOMER"))).thenReturn(300L);

        mockMvc.perform(post("/auth/register/otp")
                        .contentType("application/json")
                        .content("{\"mobileNumber\":\"+919876543210\",\"role\":\"CUSTOMER\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("OTP_SENT"))
                .andExpect(jsonPath("$.expiresInSeconds").value(300));
    }

    @Test
    void requestOtp_invalidNumber_returns400ValidationError() throws Exception {
        mockMvc.perform(post("/auth/register/otp")
                        .contentType("application/json")
                        .content("{\"mobileNumber\":\"12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void verify_valid_returns201WithTokens() throws Exception {
        when(registrationService.verifyOtp(eq("+919876543210"), eq("123456")))
                .thenReturn(new VerificationResult("user-1", List.of("CUSTOMER"),
                        new TokenPair("access", "refresh", 900L)));

        mockMvc.perform(post("/auth/register/verify")
                        .contentType("application/json")
                        .content("{\"mobileNumber\":\"+919876543210\",\"otp\":\"123456\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.refreshToken").value("refresh"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
    }

    @Test
    void verify_lockout_returns429WithRetryAfterHeader() throws Exception {
        when(registrationService.verifyOtp(eq("+919876543210"), eq("000000")))
                .thenThrow(new RegistrationException(HttpStatus.TOO_MANY_REQUESTS,
                        "OTP_SESSION_LOCKED", "locked", 1800L));

        mockMvc.perform(post("/auth/register/verify")
                        .contentType("application/json")
                        .content("{\"mobileNumber\":\"+919876543210\",\"otp\":\"000000\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1800"))
                .andExpect(jsonPath("$.errorCode").value("OTP_SESSION_LOCKED"));
    }

    @Test
    void introspect_validToken_returnsActive() throws Exception {
        when(introspectionService.introspect("good-token"))
                .thenReturn(Map.of("active", true, "sub", "user-1", "roles", List.of("CUSTOMER")));

        mockMvc.perform(get("/auth/introspect").header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.sub").value("user-1"));
    }

    @Test
    void introspect_invalidToken_returnsInactive() throws Exception {
        when(introspectionService.introspect("bad-token")).thenReturn(Map.of("active", false));

        mockMvc.perform(get("/auth/introspect").param("token", "bad-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }
}
