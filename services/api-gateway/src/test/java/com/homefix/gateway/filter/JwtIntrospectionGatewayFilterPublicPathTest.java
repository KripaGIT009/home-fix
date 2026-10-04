package com.homefix.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link JwtIntrospectionGatewayFilter#isPublic(String)}, the allow-list that lets a request
 * through the gateway without a token.
 *
 * <p>The cases that matter are the near misses: a prefix must match on a whole segment, and the
 * decision must be made on the decoded, dot-segment-resolved path, so neither a longer word that
 * merely starts with a public prefix nor an encoded or {@code ..} spelling of a protected path is
 * treated as public.
 */
class JwtIntrospectionGatewayFilterPublicPathTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/auth/register/otp",
            "/auth/register/verify",
            "/auth/login/password",
            "/auth/login/social",
            "/auth/token/refresh",
            "/auth/introspect",
            "/auth/login",
            "/actuator/health",
            "/health",
            "/health/liveness",
            // Razorpay's signed webhook.
            "/payments/webhooks/razorpay",
            // Email sign-up (under /auth/register), reset and invitations.
            "/auth/register/email/verify",
            "/auth/password/forgot",
            "/auth/invitations/abc123/acceptance",
            // Encoded spellings of public paths stay public: the same decoded path is served.
            "/auth/login/%73ocial",
            "//auth/login/password"
    })
    void authEntryPointsAndProbes_arePublic(String path) {
        assertThat(JwtIntrospectionGatewayFilter.isPublic(path)).as(path).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // A public prefix followed by more characters in the same segment is a different path.
            "/auth/loginX",
            "/auth/login-as-admin",
            "/auth/registerAdmin",
            "/auth/introspector",
            "/auth/token/refreshAll",
            "/healthz",
            "/health-anything",
            "/actuatorfoo",
            // Not on the list: revoking a session through the gateway needs a valid token.
            "/auth/logout",
            // The signed-in credential endpoints stay behind a token.
            "/auth/me",
            "/auth/me/password",
            "/auth/passwordless",
            "/admin/invitations",
            // Ordinary protected paths.
            "/bookings/123",
            "/admin/users",
            // Only the Razorpay webhook is public, not the payments API around it.
            "/payments",
            "/payments/webhooks",
            "/payments/webhooks/razorpayX",
            "/payments/7e4b1c93-0d58-4a26-bf71-3c9e5d2a8b64/razorpay/verify",
            "/payments/webhooks/razorpay/../../settlements",
            // Dot segments and encoded separators resolve to a protected path.
            "/auth/register/../../admin/users",
            "/auth/login/%2e%2e/%2e%2e/bookings/1",
            "/auth/login%2F..%2F..%2Fadmin%2Fusers",
            "/health/../bookings/1",
            // A malformed escape is never public.
            "/auth/login/%zz"
    })
    void nearMissesAndProtectedPaths_areNotPublic(String path) {
        assertThat(JwtIntrospectionGatewayFilter.isPublic(path)).as(path).isFalse();
    }
}
