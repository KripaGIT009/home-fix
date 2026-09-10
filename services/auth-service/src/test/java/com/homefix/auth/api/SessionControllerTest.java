package com.homefix.auth.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.homefix.auth.social.SocialIdentityException;
import com.homefix.auth.social.SocialLoginService;
import com.homefix.auth.social.SocialLoginService.SocialLoginResult;
import com.homefix.auth.social.SocialProvider;
import com.homefix.auth.token.RefreshService;
import com.homefix.auth.token.RefreshService.RefreshResult;
import com.homefix.auth.token.TokenException;
import com.homefix.auth.token.TokenPair;

/**
 * Web-layer tests for the session endpoints (Requirement 1.5, 1.9, 1.10, 1.12) using standalone
 * MockMvc with the shared error-envelope advice.
 */
class SessionControllerTest {

    private RefreshService refreshService;
    private SocialLoginService socialLoginService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        refreshService = mock(RefreshService.class);
        socialLoginService = mock(SocialLoginService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SessionController(refreshService, socialLoginService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ----- Refresh rotation (Requirement 1.9) -----

    @Test
    void refresh_valid_returns200WithRotatedTokens() throws Exception {
        when(refreshService.refresh(eq("r-old")))
                .thenReturn(new RefreshResult("user-1", List.of("CUSTOMER"),
                        new TokenPair("new-access", "r-new", 900L)));

        mockMvc.perform(post("/auth/token/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"r-old\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"))
                .andExpect(jsonPath("$.refreshToken").value("r-new"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
    }

    @Test
    void refresh_replay_returns401WithErrorCode() throws Exception {
        when(refreshService.refresh(eq("r-replayed")))
                .thenThrow(TokenException.replayDetected());

        mockMvc.perform(post("/auth/token/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"r-replayed\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("REFRESH_TOKEN_REPLAY"));
    }

    @Test
    void refresh_missingToken_returns400() throws Exception {
        mockMvc.perform(post("/auth/token/refresh")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    // ----- Social login (Requirement 1.5) -----

    @Test
    void socialLogin_valid_returns200WithTokens() throws Exception {
        when(socialLoginService.login(eq(SocialProvider.GOOGLE), eq("id-token")))
                .thenReturn(new SocialLoginResult("user-9", List.of("CUSTOMER"),
                        new TokenPair("access", "refresh", 900L)));

        mockMvc.perform(post("/auth/login/social")
                        .contentType("application/json")
                        .content("{\"provider\":\"GOOGLE\",\"identityToken\":\"id-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-9"))
                .andExpect(jsonPath("$.accessToken").value("access"));
    }

    @Test
    void socialLogin_invalidToken_returns401WithErrorCode() throws Exception {
        when(socialLoginService.login(eq(SocialProvider.APPLE), eq("bad")))
                .thenThrow(SocialIdentityException.invalidToken());

        mockMvc.perform(post("/auth/login/social")
                        .contentType("application/json")
                        .content("{\"provider\":\"APPLE\",\"identityToken\":\"bad\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("SOCIAL_IDENTITY_TOKEN_INVALID"));
    }

    // ----- Logout (Requirement 1.12) -----

    @Test
    void logout_valid_returns204AndRevokes() throws Exception {
        mockMvc.perform(post("/auth/logout")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"r-1\"}"))
                .andExpect(status().isNoContent());

        verify(refreshService).logout("r-1");
    }

    @Test
    void logout_missingToken_returns400() throws Exception {
        mockMvc.perform(post("/auth/logout")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
