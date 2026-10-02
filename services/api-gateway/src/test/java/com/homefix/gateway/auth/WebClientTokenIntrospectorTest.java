package com.homefix.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import com.homefix.gateway.config.GatewayBeansConfig;
import com.homefix.gateway.config.GatewaySecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * The HTTP introspection adapter's mapping of Auth Service responses, including the {@code exp}
 * claim that bounds {@link CachingTokenIntrospector}, and the bean wiring that wraps it in that
 * cache. The WebClient is driven by a stub exchange function, so no server is started.
 */
class WebClientTokenIntrospectorTest {

    private final GatewaySecurityProperties properties = new GatewaySecurityProperties();

    @Test
    void activeResponseCarriesSubjectRolesAndExpiry() {
        AtomicReference<ClientRequest> sent = new AtomicReference<>();
        WebClientTokenIntrospector introspector = introspector(sent, HttpStatus.OK,
                "{\"active\":true,\"sub\":\"user-1\",\"roles\":[\"CUSTOMER\"],\"iss\":\"\",\"exp\":1790000000}");

        IntrospectionResult result = introspector.introspect("jwt-1").block();

        assertThat(result.active()).isTrue();
        assertThat(result.subject()).isEqualTo("user-1");
        assertThat(result.roles()).containsExactly("CUSTOMER");
        assertThat(result.expiresAt()).isEqualTo(Instant.ofEpochSecond(1_790_000_000L));
        assertThat(sent.get().headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer jwt-1");
    }

    @Test
    void zeroOrMissingExpiryYieldsNoExpiry() {
        assertThat(introspector(new AtomicReference<>(), HttpStatus.OK,
                "{\"active\":true,\"sub\":\"u\",\"roles\":[],\"exp\":0}").introspect("t").block().expiresAt())
                .isNull();
        assertThat(introspector(new AtomicReference<>(), HttpStatus.OK,
                "{\"active\":true,\"sub\":\"u\"}").introspect("t").block().expiresAt())
                .isNull();
    }

    @Test
    void inactiveResponseIsInactive() {
        IntrospectionResult result = introspector(new AtomicReference<>(), HttpStatus.OK, "{\"active\":false}")
                .introspect("t").block();

        assertThat(result.active()).isFalse();
        assertThat(result.expiresAt()).isNull();
    }

    @Test
    void errorStatusIsInactive() {
        assertThat(introspector(new AtomicReference<>(), HttpStatus.SERVICE_UNAVAILABLE, "{}")
                .introspect("t").block().active()).isFalse();
    }

    @Test
    void transportFailureIsInactive() {
        WebClient failing = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new IllegalStateException("connection refused")))
                .build();

        assertThat(new WebClientTokenIntrospector(failing, properties).introspect("t").block().active())
                .isFalse();
    }

    @Test
    void exchangeThatNeverCompletesTimesOutAsInactive() {
        // Stands in for a stalled DNS lookup, pool acquisition or TCP connect, none of which the
        // HTTP client's responseTimeout covers.
        properties.getAuth().setIntrospectTimeout(java.time.Duration.ofMillis(100));
        WebClient stalled = WebClient.builder()
                .exchangeFunction(request -> Mono.never())
                .build();

        long started = System.nanoTime();
        IntrospectionResult result = new WebClientTokenIntrospector(stalled, properties)
                .introspect("t").block(java.time.Duration.ofSeconds(5));

        assertThat(result.active()).isFalse();
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(2));
    }

    @Test
    void connectToAnUnansweringAddressIsBoundedByTheIntrospectTimeout() {
        // 10.255.255.1 is normally unrouted, so the SYN goes unanswered and only the connect
        // timeout ends it (a network that rejects it outright fails even sooner). Drives the
        // client directly, without the introspector's own cap, to show the client's bound alone.
        properties.getAuth().setIntrospectTimeout(java.time.Duration.ofMillis(300));
        properties.getAuth().setIntrospectUri("http://10.255.255.1:81/auth/introspect");
        GatewayBeansConfig config = new GatewayBeansConfig();
        reactor.netty.resources.ConnectionProvider pool = config.introspectionConnectionProvider(properties);
        try {
            WebClient client = config.gatewayWebClient(properties, pool);

            long started = System.nanoTime();
            Throwable failure = client.get().uri(properties.getAuth().getIntrospectUri()).retrieve()
                    .bodyToMono(String.class)
                    .map(body -> (Throwable) null)
                    .onErrorResume(Mono::just)
                    .block(java.time.Duration.ofSeconds(10));

            assertThat(failure).isNotNull();
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(java.time.Duration.ofSeconds(5));
        } finally {
            pool.dispose();
        }
    }

    // ------------------------------------------------------------------ bean wiring

    @Test
    void productionBeanIsCachedByDefault() {
        TokenIntrospector bean = new GatewayBeansConfig().tokenIntrospector(WebClient.create(), properties);

        assertThat(bean).isInstanceOf(CachingTokenIntrospector.class);
    }

    @Test
    void zeroTtlDisablesTheCache() {
        properties.getAuth().getIntrospectionCache().setTtl(java.time.Duration.ZERO);

        TokenIntrospector bean = new GatewayBeansConfig().tokenIntrospector(WebClient.create(), properties);

        assertThat(bean).isInstanceOf(WebClientTokenIntrospector.class);
    }

    private WebClientTokenIntrospector introspector(AtomicReference<ClientRequest> sent,
                                                    HttpStatus status, String json) {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    sent.set(request);
                    return Mono.just(ClientResponse.create(status)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(json)
                            .build());
                })
                .build();
        return new WebClientTokenIntrospector(client, properties);
    }
}
