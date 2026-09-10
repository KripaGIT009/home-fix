package com.homefix.auth.social;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Key;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.homefix.auth.config.SocialLoginProperties;
import com.homefix.auth.config.SocialLoginProperties.ProviderConfig;

import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;

/**
 * {@link OidcKeyLocator} that fetches and caches each provider's JWKS document and returns the
 * public key matching the token's {@code kid} (Requirement 23.10).
 *
 * <p>Keys are cached per provider; a cache miss (unknown {@code kid}) triggers a single refresh
 * to pick up key rotation before failing. Network/parse failures surface as a
 * {@link SocialIdentityException} so the login endpoint returns 401 rather than 500.
 */
@Component
public class JwksOidcKeyLocator implements OidcKeyLocator {

    private static final Logger log = LoggerFactory.getLogger(JwksOidcKeyLocator.class);

    private final Map<SocialProvider, ProviderConfig> configs = new HashMap<>();
    private final Map<SocialProvider, Map<String, Key>> cache = new ConcurrentHashMap<>();
    private final HttpClient httpClient;

    public JwksOidcKeyLocator(SocialLoginProperties properties) {
        this.configs.put(SocialProvider.GOOGLE, properties.getGoogle());
        this.configs.put(SocialProvider.APPLE, properties.getApple());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public Key locate(SocialProvider provider, String keyId) {
        if (keyId == null || keyId.isBlank()) {
            throw SocialIdentityException.invalidToken();
        }
        Key key = cachedKey(provider, keyId);
        if (key == null) {
            // Possible key rotation — refresh once before giving up.
            refresh(provider);
            key = cachedKey(provider, keyId);
        }
        if (key == null) {
            throw SocialIdentityException.invalidToken();
        }
        return key;
    }

    private Key cachedKey(SocialProvider provider, String keyId) {
        Map<String, Key> keys = cache.get(provider);
        return keys == null ? null : keys.get(keyId);
    }

    private void refresh(SocialProvider provider) {
        ProviderConfig config = configs.get(provider);
        if (config == null || config.getJwksUri() == null || config.getJwksUri().isBlank()) {
            throw SocialIdentityException.unsupportedProvider(String.valueOf(provider));
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getJwksUri()))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("JWKS fetch returned HTTP " + response.statusCode());
            }
            JwkSet jwkSet = Jwks.setParser().build().parse(response.body());
            Map<String, Key> keys = new HashMap<>();
            for (Jwk<?> jwk : jwkSet.getKeys()) {
                if (jwk.getId() != null) {
                    keys.put(jwk.getId(), jwk.toKey());
                }
            }
            cache.put(provider, keys);
        } catch (SocialIdentityException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw SocialIdentityException.invalidToken();
        } catch (RuntimeException | java.io.IOException ex) {
            log.warn("Failed to refresh JWKS for provider {}", provider);
            throw SocialIdentityException.invalidToken();
        }
    }
}
