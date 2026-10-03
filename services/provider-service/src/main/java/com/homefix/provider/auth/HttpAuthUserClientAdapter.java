package com.homefix.provider.auth;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.homefix.provider.service.ProviderException;
import com.homefix.shared.resilience.FallbackDecision;
import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.homefix.shared.resilience.TimeoutProfile;
import com.homefix.shared.resilience.TransientFailures;

/**
 * {@link AuthUserClientPort} adapter over the Auth Service's internal user endpoints
 * (Requirements MT-2.2, MT-2.4, MT-3.2):
 * {@code GET /internal/users/by-mobile?mobileNumber=}, {@code POST} / {@code DELETE
 * /internal/users/{userId}/roles/TENANT_ADMIN} and {@code GET /internal/users/{userId}/contact}.
 *
 * <p>Every call presents the shared service credential ({@code X-Internal-Api-Key}) and runs
 * under the shared resilience stack (Requirement 24): a per-attempt timeout that also bounds the
 * socket, retry for transient failures (timeouts, 5xx) and a circuit breaker keyed on
 * {@value #DEPENDENCY}. A Platform_Admin is waiting on the click, so the budget is the
 * {@link TimeoutProfile#CRITICAL_PATH} 5 s rather than the 15 s standard. Retrying the role grant
 * and revoke is safe because both are idempotent on the Auth Service.
 *
 * <p>A 404 is an answer, not an outage: "no account has that number" becomes an empty result and
 * counts against nothing. Everything else that is not a success — unreachable, timed out, 5xx
 * after retries, breaker open, or a refused credential — becomes 503 {@code AUTH_UNAVAILABLE},
 * because mistaking "could not ask" for "no such user" would show an Admin a false
 * {@code USER_NOT_FOUND}. The contact lookup alone fails soft (display only).
 *
 * <p>Always active. A missing credential is logged as an ERROR at startup and every call then
 * fails with 503, rather than taking the rest of the Provider Service down over it.
 */
@Component
public class HttpAuthUserClientAdapter implements AuthUserClientPort {

    private static final Logger log = LoggerFactory.getLogger(HttpAuthUserClientAdapter.class);

    /** Downstream dependency name used for breaker keying, metrics, and the WARN log. */
    static final String DEPENDENCY = "auth-service";

    /** Header carrying the shared service credential the Auth Service expects on /internal/**. */
    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    private final RestClient restClient;
    private final boolean configured;
    private final ResilientCall<Optional<AuthUser>> lookupCall;
    private final ResilientCall<Boolean> roleCall;
    private final ResilientCall<Optional<String>> contactCall;

    @Autowired
    public HttpAuthUserClientAdapter(
            ResilienceFactory resilienceFactory,
            @Value("${homefix.provider.auth-service-url:http://auth-service:8081}") String baseUrl,
            @Value("${homefix.provider.internal-api-key:}") String internalApiKey) {
        this(resilienceFactory, baseUrl, internalApiKey, TimeoutProfile.CRITICAL_PATH.timeout());
    }

    /** Explicit-timeout constructor so tests can exercise the timeout path without waiting 5 s. */
    HttpAuthUserClientAdapter(ResilienceFactory resilienceFactory, String baseUrl,
                              String internalApiKey, Duration timeout) {
        this.configured = internalApiKey != null && !internalApiKey.isBlank();
        if (!configured) {
            log.error("homefix.provider.internal-api-key is not configured; Tenant administrators and "
                    + "team members cannot be looked up in the Auth Service");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) timeout.toMillis());
        requestFactory.setReadTimeout((int) timeout.toMillis());
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory);
        if (configured) {
            builder.defaultHeader(INTERNAL_KEY_HEADER, internalApiKey);
        }
        this.restClient = builder.build();
        this.lookupCall = ResilientCall.forDependency(resilienceFactory, DEPENDENCY, timeout,
                unavailableFallback("look up an account by mobile number"));
        this.roleCall = ResilientCall.forDependency(resilienceFactory, DEPENDENCY, timeout,
                unavailableFallback("change the TENANT_ADMIN role"));
        this.contactCall = ResilientCall.forDependency(resilienceFactory, DEPENDENCY, timeout,
                cause -> {
                    log.warn("Auth Service unavailable ({}); showing an administrator without a mobile "
                            + "number", DEPENDENCY, cause);
                    return Optional.empty();
                });
    }

    @Override
    public Optional<AuthUser> findByMobile(String mobileNumber) {
        requireConfigured();
        return lookupCall.execute(() -> restClient.get()
                // A template variable, so a leading '+' is percent-encoded rather than read as a space.
                .uri("/internal/users/by-mobile?mobileNumber={mobileNumber}", mobileNumber)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.value() == HttpStatus.NOT_FOUND.value()) {
                        return Optional.<AuthUser>empty();
                    }
                    requireSuccess(status, "by-mobile lookup");
                    ByMobileResponse body = response.bodyTo(ByMobileResponse.class);
                    if (body == null || body.userId() == null) {
                        throw new IllegalStateException("Auth Service answered the by-mobile lookup without a userId");
                    }
                    return Optional.of(new AuthUser(body.userId(),
                            body.roles() == null ? Set.of() : Set.copyOf(body.roles()), body.status()));
                }));
    }

    @Override
    public void grantTenantAdmin(UUID userId) {
        requireConfigured();
        roleCall.execute(() -> restClient.post()
                .uri("/internal/users/{userId}/roles/{role}", userId, TENANT_ADMIN)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.value() == HttpStatus.NOT_FOUND.value()) {
                        throw new ProviderException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                                "No account exists for user " + userId);
                    }
                    requireSuccess(status, "TENANT_ADMIN grant");
                    return Boolean.TRUE;
                }));
    }

    @Override
    public void revokeTenantAdmin(UUID userId) {
        requireConfigured();
        roleCall.execute(() -> restClient.delete()
                .uri("/internal/users/{userId}/roles/{role}", userId, TENANT_ADMIN)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    // An account that no longer exists holds no role: revoked.
                    if (status.value() != HttpStatus.NOT_FOUND.value()) {
                        requireSuccess(status, "TENANT_ADMIN revoke");
                    }
                    return Boolean.TRUE;
                }));
    }

    @Override
    public Optional<String> mobileNumberOf(UUID userId) {
        if (!configured) {
            return Optional.empty();
        }
        return contactCall.execute(() -> restClient.get()
                .uri("/internal/users/{userId}/contact", userId)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.value() == HttpStatus.NOT_FOUND.value()) {
                        return Optional.<String>empty();
                    }
                    requireSuccess(status, "contact lookup");
                    ContactResponse body = response.bodyTo(ContactResponse.class);
                    return Optional.ofNullable(body == null ? null : body.mobileNumber());
                }));
    }

    // ----------------------------------------------------------------------------------------

    /**
     * 5xx is transient so retry and the breaker act on it (Requirement 24.2); any other non-2xx
     * (a refused credential, a contract mismatch) is not retried and ends as 503 through the
     * fallback.
     */
    private static void requireSuccess(HttpStatusCode status, String what) {
        if (status.is5xxServerError()) {
            throw new TransientFailures.ServerErrorException(status.value(),
                    "Auth Service returned " + status.value() + " for the " + what);
        }
        if (!status.is2xxSuccessful()) {
            throw new IllegalStateException("Auth Service refused the " + what + " with HTTP " + status.value());
        }
    }

    private void requireConfigured() {
        if (!configured) {
            throw unavailable();
        }
    }

    /**
     * A domain refusal raised inside the call (e.g. 404 {@code USER_NOT_FOUND} on a grant) passes
     * through unchanged; every other failure is the dependency's, reported as 503.
     */
    private static <T> FallbackDecision<T> unavailableFallback(String action) {
        return cause -> {
            if (cause instanceof ProviderException refusal) {
                throw refusal;
            }
            log.warn("Auth Service unavailable ({}); could not {}", DEPENDENCY, action, cause);
            throw unavailable();
        };
    }

    private static ProviderException unavailable() {
        return new ProviderException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE",
                "The Auth Service is unavailable; nothing was changed. Try again shortly.");
    }

    /** Response body of {@code GET /internal/users/by-mobile}. */
    record ByMobileResponse(UUID userId, List<String> roles, String status) {
    }

    /** Response body of {@code GET /internal/users/{userId}/contact}, as far as this adapter reads it. */
    record ContactResponse(UUID userId, String mobileNumber) {
    }
}
