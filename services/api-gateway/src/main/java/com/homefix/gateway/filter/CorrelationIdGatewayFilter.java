package com.homefix.gateway.filter;

import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive global filter that guarantees every routed request carries an {@code X-Correlation-ID}
 * (Requirement 23.9).
 *
 * <ul>
 *   <li>If the inbound request already carries a non-blank {@code X-Correlation-ID}, that value is
 *       reused so a single id flows across service hops.</li>
 *   <li>Otherwise a fresh UUID v4 is generated.</li>
 *   <li>The resolved id is injected onto the <em>mutated forwarded request</em> so downstream
 *       microservices see it, echoed on the response header, and stored as an exchange attribute so
 *       error responses can quote it.</li>
 * </ul>
 *
 * <p>The shared servlet-based {@code CorrelationIdFilter} (Task 4) cannot run in this reactive
 * gateway, so this filter mirrors its contract for the WebFlux stack. It runs before the WAF,
 * auth, and rate-limit filters so every rejection is correlated.
 */
@Component
public class CorrelationIdGatewayFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String CORRELATION_ID_ATTR = "homefix.correlationId";
    public static final String MDC_KEY = "correlationId";

    /** Runs very early — after HTTPS redirect but before WAF/auth/rate-limit. */
    public static final int ORDER = -90;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = resolve(exchange.getRequest());

        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(h -> h.set(CORRELATION_ID_HEADER, correlationId))
                .build();

        exchange.getAttributes().put(CORRELATION_ID_ATTR, correlationId);
        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);

        ServerWebExchange mutatedExchange = exchange.mutate().request(mutated).build();

        MDC.put(MDC_KEY, correlationId);
        try {
            return chain.filter(mutatedExchange);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Returns the inbound id if present and non-blank, otherwise a new UUID v4 string. */
    public static String resolve(ServerHttpRequest request) {
        String inbound = request.getHeaders().getFirst(CORRELATION_ID_HEADER);
        if (StringUtils.hasText(inbound)) {
            return inbound.trim();
        }
        return UUID.randomUUID().toString();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
