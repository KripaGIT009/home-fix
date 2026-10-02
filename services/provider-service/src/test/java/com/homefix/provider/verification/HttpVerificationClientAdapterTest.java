package com.homefix.provider.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.provider.service.ProviderException;
import com.homefix.shared.resilience.ResilienceFactory;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests the Verification Service batch adapter against a loopback stub server: the credential and
 * body it sends, chunking, and that every failure mode — refused credential, 5xx, timeout,
 * unreachable, unconfigured key — fails closed to "nobody approved". The Admin-list half
 * ({@link VerificationAdminClientPort}) is covered at the bottom: the status lookup fails soft to
 * "unknown", and the suspend / reinstate writes are sent once and surface every failure.
 */
class HttpVerificationClientAdapterTest {

    private static final String KEY = "test-internal-key";
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final List<String> presentedKeys = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int statusToReturn = 200;
    /** When non-null, returned verbatim; otherwise every requested id is echoed as approved. */
    private volatile String bodyToReturn;
    private volatile long delayMillis = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            paths.add(exchange.getRequestURI().getPath());
            methods.add(exchange.getRequestMethod());
            String key = exchange.getRequestHeaders().getFirst(HttpVerificationClientAdapter.INTERNAL_KEY_HEADER);
            presentedKeys.add(key == null ? "<none>" : key);
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(requestBody);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String response = bodyToReturn != null ? bodyToReturn : echoAllApproved(requestBody);
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
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

    private static String echoAllApproved(String requestBody) {
        List<String> ids = new ArrayList<>();
        Matcher m = UUID_PATTERN.matcher(requestBody);
        while (m.find()) {
            ids.add("\"" + m.group() + "\"");
        }
        return "{\"approvedProviderIds\":[" + String.join(",", ids) + "]}";
    }

    private HttpVerificationClientAdapter adapter() {
        return adapter(KEY, Duration.ofSeconds(2));
    }

    private HttpVerificationClientAdapter adapter(String key, Duration timeout) {
        return new HttpVerificationClientAdapter(new ResilienceFactory(), baseUrl, key, timeout);
    }

    @Test
    void returnsTheApprovedSubsetPresentingTheServiceCredential() {
        UUID approved = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        bodyToReturn = "{\"approvedProviderIds\":[\"" + approved + "\"]}";

        Set<UUID> result = adapter().approvedAmong(List.of(approved, pending));

        assertThat(result).containsExactly(approved);
        assertThat(methods).containsExactly("POST");
        assertThat(paths).containsExactly("/internal/verifications/approved");
        assertThat(presentedKeys).containsExactly(KEY);
        assertThat(bodies.get(0)).contains("\"providerIds\"").contains(approved.toString())
                .contains(pending.toString());
    }

    @Test
    void idsTheResponseWasNotAskedAbout_areIgnored() {
        UUID asked = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        bodyToReturn = "{\"approvedProviderIds\":[\"" + asked + "\",\"" + stranger + "\"]}";

        assertThat(adapter().approvedAmong(List.of(asked))).containsExactly(asked);
    }

    @Test
    void largeCandidateSets_areSentInChunksWithinTheServersCap() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < HttpVerificationClientAdapter.MAX_IDS_PER_REQUEST + 20; i++) {
            ids.add(UUID.randomUUID());
        }

        Set<UUID> result = adapter().approvedAmong(ids);

        assertThat(result).hasSize(ids.size());
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void emptyCandidateList_makesNoCall() {
        assertThat(adapter().approvedAmong(List.of())).isEmpty();
        assertThat(requests.get()).isZero();
    }

    @Test
    void refusedCredential_failsClosedWithoutRetrying() {
        statusToReturn = 401;
        bodyToReturn = "{\"errorCode\":\"INTERNAL_AUTH_FAILED\",\"message\":\"Invalid internal credentials\"}";

        assertThat(adapter().approvedAmong(List.of(UUID.randomUUID()))).isEmpty();
        // 4xx is not transient: one attempt, no retry.
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void serverError_isRetriedThenFailsClosed() {
        statusToReturn = 503;
        bodyToReturn = "{}";

        assertThat(adapter().approvedAmong(List.of(UUID.randomUUID()))).isEmpty();
        assertThat(requests.get()).isEqualTo(ResilienceFactory.MAX_ATTEMPTS);
    }

    @Test
    void slowResponse_timesOutAndFailsClosed() {
        delayMillis = 1_000;

        Set<UUID> result = adapter(KEY, Duration.ofMillis(200)).approvedAmong(List.of(UUID.randomUUID()));

        assertThat(result).isEmpty();
    }

    @Test
    void unreachableService_failsClosed() {
        server.stop(0);

        assertThat(adapter().approvedAmong(List.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void unconfiguredKey_failsClosedWithoutCalling() {
        assertThat(adapter(" ", Duration.ofSeconds(2)).approvedAmong(List.of(UUID.randomUUID()))).isEmpty();
        assertThat(requests.get()).isZero();
    }

    // ------------------------------------------------------------------ Admin provider list

    @Test
    void statusesOf_returnsTheStatusesPresentingTheServiceCredential() {
        UUID approved = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        bodyToReturn = "{\"statuses\":{\"" + approved + "\":\"APPROVED\",\"" + stranger + "\":\"SUSPENDED\"}}";

        Optional<Map<UUID, String>> result = adapter().statusesOf(List.of(approved, unknown));

        assertThat(result).contains(Map.of(approved, "APPROVED"));
        assertThat(methods).containsExactly("POST");
        assertThat(paths).containsExactly("/internal/verifications/statuses");
        assertThat(presentedKeys).containsExactly(KEY);
        assertThat(bodies.get(0)).contains("\"providerIds\"").contains(approved.toString());
    }

    @Test
    void statusesOf_failsSoftToUnknown() {
        statusToReturn = 503;
        bodyToReturn = "{}";
        assertThat(adapter().statusesOf(List.of(UUID.randomUUID()))).isEmpty();

        statusToReturn = 401;
        assertThat(adapter().statusesOf(List.of(UUID.randomUUID()))).isEmpty();

        assertThat(adapter(" ", Duration.ofSeconds(2)).statusesOf(List.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void statusesOf_noIds_isAnEmptyKnownAnswerWithoutCalling() {
        assertThat(adapter().statusesOf(List.of())).contains(Map.of());
        assertThat(requests.get()).isZero();
    }

    @Test
    void suspend_postsTheActingAdminOnceAndReturnsTheNewStatus() {
        UUID providerId = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        bodyToReturn = "{\"providerId\":\"" + providerId + "\",\"status\":\"SUSPENDED\"}";

        String status = adapter().suspend(providerId, admin, "complaint upheld");

        assertThat(status).isEqualTo("SUSPENDED");
        assertThat(paths).containsExactly("/internal/verifications/" + providerId + "/suspend");
        assertThat(presentedKeys).containsExactly(KEY);
        assertThat(bodies.get(0)).contains("\"actorId\":\"" + admin + "\"").contains("complaint upheld");
    }

    @Test
    void reinstate_targetsTheReinstateAction() {
        UUID providerId = UUID.randomUUID();
        bodyToReturn = "{\"providerId\":\"" + providerId + "\",\"status\":\"APPROVED\"}";

        assertThat(adapter().reinstate(providerId, UUID.randomUUID(), null)).isEqualTo("APPROVED");
        assertThat(paths).containsExactly("/internal/verifications/" + providerId + "/reinstate");
    }

    @Test
    void statusChange_conflictPassesThroughWithTheDownstreamErrorCode() {
        statusToReturn = 409;
        bodyToReturn = "{\"errorCode\":\"INVALID_STATE_TRANSITION\",\"message\":\"Only a SUSPENDED provider can be reinstated\"}";

        assertThatThrownBy(() -> adapter().reinstate(UUID.randomUUID(), UUID.randomUUID(), null))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> {
                    ProviderException pe = (ProviderException) e;
                    assertThat(pe.getStatus().value()).isEqualTo(409);
                    assertThat(pe.getErrorCode()).isEqualTo("INVALID_STATE_TRANSITION");
                    assertThat(pe.getMessage()).isEqualTo("Only a SUSPENDED provider can be reinstated");
                });
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void statusChange_missingVerificationRecord_isAConflictHere() {
        statusToReturn = 404;
        bodyToReturn = "{\"errorCode\":\"VERIFICATION_NOT_FOUND\",\"message\":\"none\"}";

        assertThatThrownBy(() -> adapter().suspend(UUID.randomUUID(), UUID.randomUUID(), null))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> {
                    assertThat(((ProviderException) e).getStatus().value()).isEqualTo(409);
                    assertThat(((ProviderException) e).getErrorCode()).isEqualTo("VERIFICATION_NOT_FOUND");
                });
    }

    @Test
    void statusChange_serverErrorIsNotRetriedAndIs503() {
        statusToReturn = 503;
        bodyToReturn = "{}";

        assertThatThrownBy(() -> adapter().suspend(UUID.randomUUID(), UUID.randomUUID(), null))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> {
                    assertThat(((ProviderException) e).getStatus().value()).isEqualTo(503);
                    assertThat(((ProviderException) e).getErrorCode()).isEqualTo("VERIFICATION_UNAVAILABLE");
                });
        // A write is never retried: a retry after a suspension that landed would answer 409.
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void statusChange_refusedCredentialUnreachableOrUnconfigured_is503() {
        statusToReturn = 401;
        bodyToReturn = "{\"errorCode\":\"INTERNAL_AUTH_FAILED\",\"message\":\"Invalid internal credentials\"}";
        assertThatThrownBy(() -> adapter().suspend(UUID.randomUUID(), UUID.randomUUID(), null))
                .satisfies(e -> assertThat(((ProviderException) e).getErrorCode()).isEqualTo("VERIFICATION_UNAVAILABLE"));

        assertThatThrownBy(() -> adapter(" ", Duration.ofSeconds(2)).suspend(UUID.randomUUID(), UUID.randomUUID(), null))
                .satisfies(e -> assertThat(((ProviderException) e).getErrorCode()).isEqualTo("VERIFICATION_UNAVAILABLE"));

        server.stop(0);
        assertThatThrownBy(() -> adapter().suspend(UUID.randomUUID(), UUID.randomUUID(), null))
                .satisfies(e -> assertThat(((ProviderException) e).getErrorCode()).isEqualTo("VERIFICATION_UNAVAILABLE"));
    }
}
