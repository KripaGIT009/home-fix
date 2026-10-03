package com.homefix.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.homefix.provider.auth.AuthUserClientPort.AuthUser;
import com.homefix.provider.service.ProviderException;
import com.homefix.shared.resilience.ResilienceFactory;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests the Auth Service adapter against a loopback stub of the auth-service internal contract
 * (Requirements MT-2.2, MT-2.4, MT-3.2): the paths, methods, credential and encoded mobile number
 * it sends; 404 read as an answer; and every failure — refused credential, 5xx, timeout,
 * unreachable, unconfigured key — reported as 503 {@code AUTH_UNAVAILABLE}, never as "no such
 * user". The display-only contact lookup fails soft.
 */
class HttpAuthUserClientAdapterTest {

    private static final String KEY = "test-internal-key";

    private HttpServer server;
    private String baseUrl;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> presentedKeys = new CopyOnWriteArrayList<>();
    private volatile int statusToReturn = 200;
    private volatile String bodyToReturn = "";
    private volatile long delayMillis;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            // Raw (still encoded) path and query, so the test sees exactly what went on the wire.
            String query = exchange.getRequestURI().getRawQuery();
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
                    + (query == null ? "" : "?" + query));
            String key = exchange.getRequestHeaders().getFirst(HttpAuthUserClientAdapter.INTERNAL_KEY_HEADER);
            presentedKeys.add(key == null ? "<none>" : key);
            exchange.getRequestBody().readAllBytes();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = bodyToReturn.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusToReturn, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private HttpAuthUserClientAdapter adapter() {
        return adapter(KEY, Duration.ofSeconds(2));
    }

    private HttpAuthUserClientAdapter adapter(String key, Duration timeout) {
        return new HttpAuthUserClientAdapter(new ResilienceFactory(), baseUrl, key, timeout);
    }

    private static void assertUnavailable(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ProviderException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getErrorCode()).isEqualTo("AUTH_UNAVAILABLE");
        });
    }

    // ------------------------------------------------------------------ by-mobile

    @Test
    void findByMobile_sendsTheEncodedNumberAndTheCredential() {
        UUID user = UUID.randomUUID();
        bodyToReturn = "{\"userId\":\"" + user + "\",\"roles\":[\"SERVICE_PROVIDER\",\"CUSTOMER\"],\"status\":\"ACTIVE\"}";

        Optional<AuthUser> found = adapter().findByMobile("+919000000011");

        assertThat(found).contains(new AuthUser(user, Set.of("SERVICE_PROVIDER", "CUSTOMER"), "ACTIVE"));
        assertThat(found.get().hasRole(AuthUserClientPort.SERVICE_PROVIDER)).isTrue();
        assertThat(requests).containsExactly("GET /internal/users/by-mobile?mobileNumber=%2B919000000011");
        assertThat(presentedKeys).containsExactly(KEY);
    }

    @Test
    void findByMobile_404IsNoSuchAccount() {
        statusToReturn = 404;
        bodyToReturn = "{\"errorCode\":\"USER_NOT_FOUND\",\"message\":\"not found\"}";

        assertThat(adapter().findByMobile("+919999999999")).isEmpty();
    }

    @Test
    void findByMobile_failuresAreUnavailableNeverNotFound() {
        statusToReturn = 401;
        assertUnavailable(() -> adapter().findByMobile("+919000000011"));

        statusToReturn = 503;
        requests.clear();
        assertUnavailable(() -> adapter().findByMobile("+919000000011"));
        assertThat(requests.size()).as("5xx is retried").isGreaterThan(1);

        statusToReturn = 200;
        bodyToReturn = "not json";
        assertUnavailable(() -> adapter().findByMobile("+919000000011"));
    }

    @Test
    void findByMobile_timeoutIsUnavailable() {
        delayMillis = 1_500;
        bodyToReturn = "{\"userId\":\"" + UUID.randomUUID() + "\",\"roles\":[],\"status\":\"ACTIVE\"}";

        assertUnavailable(() -> adapter(KEY, Duration.ofMillis(300)).findByMobile("+919000000011"));
    }

    @Test
    void unreachableOrUnconfigured_isUnavailable() {
        server.stop(0);
        assertUnavailable(() -> adapter().findByMobile("+919000000011"));
        assertUnavailable(() -> adapter("", Duration.ofSeconds(1)).findByMobile("+919000000011"));
        assertUnavailable(() -> adapter(" ", Duration.ofSeconds(1)).grantTenantAdmin(UUID.randomUUID()));
        assertThat(adapter("", Duration.ofSeconds(1)).mobileNumberOf(UUID.randomUUID())).isEmpty();
    }

    // ------------------------------------------------------------------ grant / revoke

    @Test
    void grantAndRevoke_hitTheRoleEndpointWithTheCredential() {
        UUID user = UUID.randomUUID();
        bodyToReturn = "{\"userId\":\"" + user + "\",\"roles\":[\"TENANT_ADMIN\"],\"status\":\"ACTIVE\"}";

        adapter().grantTenantAdmin(user);
        statusToReturn = 204;
        bodyToReturn = "";
        adapter().revokeTenantAdmin(user);

        assertThat(requests).containsExactly(
                "POST /internal/users/" + user + "/roles/TENANT_ADMIN",
                "DELETE /internal/users/" + user + "/roles/TENANT_ADMIN");
        assertThat(presentedKeys).containsOnly(KEY);
    }

    @Test
    void grant_ofAMissingAccountIsUserNotFound_revokeOfOneIsDone() {
        statusToReturn = 404;
        bodyToReturn = "{\"errorCode\":\"USER_NOT_FOUND\",\"message\":\"not found\"}";

        assertThatThrownBy(() -> adapter().grantTenantAdmin(UUID.randomUUID()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getErrorCode()).isEqualTo("USER_NOT_FOUND");
                });
        adapter().revokeTenantAdmin(UUID.randomUUID());
    }

    @Test
    void grantAndRevoke_failuresAreUnavailable() {
        statusToReturn = 500;
        assertUnavailable(() -> adapter().grantTenantAdmin(UUID.randomUUID()));
        assertUnavailable(() -> adapter().revokeTenantAdmin(UUID.randomUUID()));

        statusToReturn = 400;
        bodyToReturn = "{\"errorCode\":\"ROLE_NOT_MANAGEABLE\",\"message\":\"no\"}";
        assertUnavailable(() -> adapter().grantTenantAdmin(UUID.randomUUID()));
    }

    // ------------------------------------------------------------------ contact

    @Test
    void mobileNumberOf_readsTheContactAndFailsSoft() {
        UUID user = UUID.randomUUID();
        bodyToReturn = "{\"userId\":\"" + user + "\",\"mobileNumber\":\"+919000000031\",\"emailAddress\":null}";

        assertThat(adapter().mobileNumberOf(user)).contains("+919000000031");
        assertThat(requests).containsExactly("GET /internal/users/" + user + "/contact");

        statusToReturn = 404;
        assertThat(adapter().mobileNumberOf(user)).isEmpty();
        statusToReturn = 500;
        assertThat(adapter().mobileNumberOf(user)).isEmpty();
    }
}
