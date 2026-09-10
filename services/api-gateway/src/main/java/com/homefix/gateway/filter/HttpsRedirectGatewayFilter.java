package com.homefix.gateway.filter;

import com.homefix.gateway.config.GatewaySecurityProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * Redirects plain HTTP requests to their HTTPS equivalent with a 301 (Requirement 23.7) and, by
 * running before all other gateway logic, ensures only HTTPS traffic is ever routed
 * (Requirement 23.8).
 *
 * <p>TLS termination and rejection of TLS &lt; 1.2 happen at the AWS load balancer / API Gateway
 * edge (Task 1); the effective protocol is conveyed to this gateway via the {@code X-Forwarded-Proto}
 * header. This filter treats a request as HTTP when neither the URI scheme nor
 * {@code X-Forwarded-Proto} indicates {@code https}.
 */
@Component
public class HttpsRedirectGatewayFilter implements GlobalFilter, Ordered {

    /** Highest precedence — evaluated before correlation, WAF, auth, and rate-limit. */
    public static final int ORDER = -100;

    private final GatewaySecurityProperties properties;

    public HttpsRedirectGatewayFilter(GatewaySecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isHttpsEnforced() || isSecure(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        URI original = exchange.getRequest().getURI();
        URI httpsUri = toHttps(original);

        exchange.getResponse().setStatusCode(HttpStatus.MOVED_PERMANENTLY);
        exchange.getResponse().getHeaders().set(HttpHeaders.LOCATION, httpsUri.toString());
        return exchange.getResponse().setComplete();
    }

    /** True when the request reached (or was proxied as) HTTPS. */
    static boolean isSecure(ServerHttpRequest request) {
        String scheme = request.getURI().getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return true;
        }
        String forwardedProto = request.getHeaders().getFirst("X-Forwarded-Proto");
        return forwardedProto != null && "https".equalsIgnoreCase(forwardedProto.trim());
    }

    /** Rebuilds the URI on the https scheme, dropping any explicit :80 port. */
    static URI toHttps(URI original) {
        int port = original.getPort();
        int newPort = (port == 80) ? -1 : port;
        try {
            return new URI(
                    "https",
                    original.getUserInfo(),
                    original.getHost(),
                    newPort,
                    original.getPath(),
                    original.getQuery(),
                    original.getFragment());
        } catch (Exception e) {
            // Fallback: string replacement of the scheme.
            return URI.create(original.toString().replaceFirst("^http://", "https://"));
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
