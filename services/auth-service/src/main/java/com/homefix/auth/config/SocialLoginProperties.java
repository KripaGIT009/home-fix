package com.homefix.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OAuth2/OIDC configuration for social login providers (Requirement 1.5, 23.10).
 *
 * <pre>
 * homefix:
 *   auth:
 *     social:
 *       google:
 *         issuer: https://accounts.google.com
 *         audience: &lt;google-oauth-client-id&gt;
 *         jwks-uri: https://www.googleapis.com/oauth2/v3/certs
 *       apple:
 *         issuer: https://appleid.apple.com
 *         audience: &lt;apple-services-id&gt;
 *         jwks-uri: https://appleid.apple.com/auth/keys
 * </pre>
 */
@ConfigurationProperties(prefix = "homefix.auth.social")
public class SocialLoginProperties {

    private ProviderConfig google = new ProviderConfig();
    private ProviderConfig apple = new ProviderConfig();

    public ProviderConfig getGoogle() {
        return google;
    }

    public void setGoogle(ProviderConfig google) {
        this.google = google;
    }

    public ProviderConfig getApple() {
        return apple;
    }

    public void setApple(ProviderConfig apple) {
        this.apple = apple;
    }

    /**
     * Per-provider OIDC settings used to validate the {@code iss}, {@code aud}, and {@code exp}
     * claims of the identity token.
     */
    public static class ProviderConfig {

        /** Expected {@code iss} claim (OIDC issuer identifier). */
        private String issuer;

        /** Expected {@code aud} claim (this platform's OAuth client / services id). */
        private String audience;

        /** URI of the provider's JWKS document used to verify the token signature. */
        private String jwksUri;

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getJwksUri() {
            return jwksUri;
        }

        public void setJwksUri(String jwksUri) {
            this.jwksUri = jwksUri;
        }
    }
}
