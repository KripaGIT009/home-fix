package com.homefix.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.homefix.auth.config.AuthTokenProperties;
import com.homefix.auth.config.OtpProperties;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.otp.OtpCodeGenerator;
import com.homefix.auth.registration.RegistrationException;
import com.homefix.auth.registration.RegistrationService;
import com.homefix.auth.support.FakeSmsGateway;
import com.homefix.auth.support.InMemoryOtpStore;
import com.homefix.auth.support.InMemoryRefreshTokenStore;
import com.homefix.auth.token.RefreshTokenRecord;
import com.homefix.auth.token.TokenException;
import com.homefix.auth.token.TokenPair;
import com.homefix.auth.token.TokenService;
import com.homefix.shared.security.SecurityProperties;

import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-based tests for the Auth Service correctness properties 25–26 (design.md "Correctness
 * Properties", Requirement 1). Each property runs a minimum of 100 tries and is tagged with the
 * required {@code Feature: homefix-platform, Property N} label.
 *
 * <p>Both properties exercise the real production logic — {@link RegistrationService} over an
 * in-memory OTP store with a simulated clock (Property 25), and {@link TokenService} over an
 * in-memory refresh-token store (Property 26) — with no Redis, DB, or Spring context.
 */
class AuthPropertiesTest {

    private static final String SECRET = "unit-test-signing-secret-that-is-32b+";

    // ============================================================================================
    // Property 25: OTP session lockout
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 25: OTP session lockout")
    void fiveConsecutiveWrongSubmissionsLockSessionForExactlyThirtyMinutes(
            @ForAll @IntRange(min = 5, max = 10) int maxAttempts,
            @ForAll @IntRange(min = 0, max = 4) int extraWrongTriesDuringLockout) {

        String phone = "+919876543210";

        OtpProperties otpProperties = new OtpProperties();
        otpProperties.setTtl(Duration.ofMinutes(5));
        otpProperties.setLength(6);
        otpProperties.setMaxVerifyAttempts(maxAttempts);
        otpProperties.setLockout(Duration.ofMinutes(30));
        otpProperties.setMaxRequestsPerWindow(1000);
        otpProperties.setRateLimitWindow(Duration.ofHours(1));

        InMemoryOtpStore otpStore = new InMemoryOtpStore();
        OtpCodeGenerator codeGenerator = new OtpCodeGenerator();
        FakeSmsGateway sms = new FakeSmsGateway();
        // The OTP lockout path never persists an account, so a bare mock repository suffices.
        UserAccountRepository users = org.mockito.Mockito.mock(UserAccountRepository.class);
        TokenService tokenService = tokenService(new InMemoryRefreshTokenStore());
        RegistrationService service = new RegistrationService(otpProperties, otpStore, codeGenerator,
                sms, users, tokenService);

        service.requestOtp(phone, "CUSTOMER");
        String correctCode = sms.lastOtpCode();
        String wrongCode = wrongCode(correctCode);

        // Submit `maxAttempts - 1` wrong codes: not yet locked, each rejected as OTP_INCORRECT.
        for (int i = 0; i < maxAttempts - 1; i++) {
            assertThatThrownBy(() -> service.verifyOtp(phone, wrongCode))
                    .isInstanceOf(RegistrationException.class)
                    .satisfies(ex -> assertThat(((RegistrationException) ex).getErrorCode())
                            .isEqualTo("OTP_INCORRECT"));
            assertThat(otpStore.isLocked(phone)).isFalse();
        }

        // The Nth wrong submission crosses the threshold and locks the session.
        assertThatThrownBy(() -> service.verifyOtp(phone, wrongCode))
                .isInstanceOf(RegistrationException.class)
                .satisfies(ex -> assertThat(((RegistrationException) ex).getErrorCode())
                        .isEqualTo("OTP_SESSION_LOCKED"));
        assertThat(otpStore.isLocked(phone)).isTrue();

        // No attempt succeeds during lockout — not even the CORRECT code, and not additional
        // wrong codes: every attempt is rejected as OTP_SESSION_LOCKED (Property 25).
        for (int i = 0; i < extraWrongTriesDuringLockout; i++) {
            assertThatThrownBy(() -> service.verifyOtp(phone, wrongCode))
                    .isInstanceOf(RegistrationException.class)
                    .satisfies(ex -> assertThat(((RegistrationException) ex).getErrorCode())
                            .isEqualTo("OTP_SESSION_LOCKED"));
        }
        assertThatThrownBy(() -> service.verifyOtp(phone, correctCode))
                .isInstanceOf(RegistrationException.class)
                .satisfies(ex -> assertThat(((RegistrationException) ex).getErrorCode())
                        .isEqualTo("OTP_SESSION_LOCKED"));

        // Still locked one second before the 30-minute window elapses.
        otpStore.advance(Duration.ofMinutes(30).minusSeconds(1));
        assertThat(otpStore.isLocked(phone)).isTrue();
        assertThatThrownBy(() -> service.verifyOtp(phone, correctCode))
                .isInstanceOf(RegistrationException.class)
                .satisfies(ex -> assertThat(((RegistrationException) ex).getErrorCode())
                        .isEqualTo("OTP_SESSION_LOCKED"));

        // Exactly at 30 minutes the lockout has expired (locked for exactly 30 minutes).
        otpStore.advance(Duration.ofSeconds(1));
        assertThat(otpStore.isLocked(phone)).isFalse();
        // The prior session was cleared by the lock, so a post-lockout verify hits the
        // "no active OTP" path rather than remaining locked — the lockout is over.
        assertThatThrownBy(() -> service.verifyOtp(phone, correctCode))
                .isInstanceOf(RegistrationException.class)
                .satisfies(ex -> assertThat(((RegistrationException) ex).getErrorCode())
                        .isEqualTo("OTP_EXPIRED_OR_MISSING"));
    }

    // ============================================================================================
    // Property 26: Refresh token replay detection and family invalidation
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 26: Refresh token replay detection and family invalidation")
    void replayingARefreshTokenInvalidatesTheEntireFamily(
            @ForAll @IntRange(min = 0, max = 6) int rotationsBeforeReplay) {

        InMemoryRefreshTokenStore store = new InMemoryRefreshTokenStore();
        TokenService tokenService = tokenService(store);
        String subject = UUID.randomUUID().toString();
        List<String> roles = List.of("CUSTOMER");

        // Start a family and rotate it a number of times, tracking every issued token.
        TokenPair initial = tokenService.issueTokens(subject, roles);
        List<String> familyTokens = new ArrayList<>();
        familyTokens.add(initial.refreshToken());

        String current = initial.refreshToken();
        for (int i = 0; i < rotationsBeforeReplay; i++) {
            RefreshTokenRecord consumed = tokenService.consumeRefreshTokenForRotation(current);
            TokenPair rotated = tokenService.issueRotatedTokens(consumed, roles);
            familyTokens.add(rotated.refreshToken());
            current = rotated.refreshToken();
        }

        // Replay: re-present an already-rotated token. The only already-used token is the one
        // that was current before the latest rotation. If there were no rotations, we consume
        // the initial token once and then replay it.
        String replayedToken;
        if (rotationsBeforeReplay == 0) {
            tokenService.consumeRefreshTokenForRotation(current); // first legitimate use
            replayedToken = current;                              // now used -> replay next
        } else {
            // `familyTokens` second-to-last entry was consumed during the last rotation.
            replayedToken = familyTokens.get(familyTokens.size() - 2);
        }

        // Presenting the already-used token is detected as a replay and invalidates the family.
        assertThatThrownBy(() -> tokenService.consumeRefreshTokenForRotation(replayedToken))
                .isInstanceOf(TokenException.class)
                .satisfies(ex -> assertThat(((TokenException) ex).getErrorCode())
                        .isEqualTo("REFRESH_TOKEN_REPLAY"));

        // After family invalidation, NO token in the family can produce a new access token:
        // every family member is rejected with a 401 (Property 26).
        for (String token : familyTokens) {
            assertThatThrownBy(() -> tokenService.consumeRefreshTokenForRotation(token))
                    .as("family token must be invalidated after replay")
                    .isInstanceOf(TokenException.class);
            assertThat(tokenService.findRefreshToken(token)).isEmpty();
        }
    }

    // ============================================================================================
    // Helpers
    // ============================================================================================

    private TokenService tokenService(InMemoryRefreshTokenStore store) {
        SecurityProperties security = new SecurityProperties();
        security.setJwtSecret(SECRET);
        AuthTokenProperties tokenProps = new AuthTokenProperties();
        tokenProps.setAccessTtl(Duration.ofMinutes(15));
        tokenProps.setRefreshTtl(Duration.ofDays(30));
        return new TokenService(security, tokenProps, store);
    }

    /** A code guaranteed to differ from {@code correctCode} but of the same digit shape. */
    private static String wrongCode(String correctCode) {
        StringBuilder sb = new StringBuilder(correctCode.length());
        for (int i = 0; i < correctCode.length(); i++) {
            char c = correctCode.charAt(i);
            sb.append(c == '0' ? '1' : '0');
        }
        String candidate = sb.toString();
        return candidate.equals(correctCode) ? "999999" : candidate;
    }
}
