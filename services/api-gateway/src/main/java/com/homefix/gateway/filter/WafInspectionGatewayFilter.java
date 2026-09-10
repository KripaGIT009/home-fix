package com.homefix.gateway.filter;

import com.homefix.gateway.support.GatewayErrorWriter;
import com.homefix.gateway.waf.AttackPatternMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Application-layer WAF inspection: blocks requests whose path, query parameters, or inspected
 * headers match OWASP SQL-injection or XSS signatures (Requirement 23.6).
 *
 * <p>On a match the request is rejected with {@code 400 Bad Request} and a <em>generic</em> error
 * body — the response never reveals which rule fired or the offending value, so no internal detail
 * leaks (Requirement 23.6). The specific category and correlation id are logged server-side for
 * operators. The offending value itself is never logged, keeping PII/attacker payloads out of logs
 * (Requirement 26.4).
 */
@Component
public class WafInspectionGatewayFilter implements GlobalFilter, Ordered {

    /** Runs after correlation-id assignment but before auth and rate limiting. */
    public static final int ORDER = -80;

    private static final Logger log = LoggerFactory.getLogger(WafInspectionGatewayFilter.class);

    /** Headers worth inspecting for reflected payloads without over-scanning every hop header. */
    private static final List<String> INSPECTED_HEADERS =
            List.of("User-Agent", "Referer", "X-Forwarded-For", "Cookie");

    private final AttackPatternMatcher matcher;
    private final GatewayErrorWriter errorWriter;

    public WafInspectionGatewayFilter(AttackPatternMatcher matcher, GatewayErrorWriter errorWriter) {
        this.matcher = matcher;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        AttackPatternMatcher.Category category = inspect(exchange);
        if (category != null) {
            log.warn("WAF blocked request category={} path={}",
                    category, exchange.getRequest().getPath().value());
            return errorWriter.write(exchange, HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST", "The request was rejected.");
        }
        return chain.filter(exchange);
    }

    /** Returns the first attack category found in the path, query values, or inspected headers. */
    AttackPatternMatcher.Category inspect(ServerWebExchange exchange) {
        var request = exchange.getRequest();

        AttackPatternMatcher.Category pathHit = matcher.classify(request.getPath().value());
        if (pathHit != null) {
            return pathHit;
        }

        for (Map.Entry<String, List<String>> entry : request.getQueryParams().entrySet()) {
            AttackPatternMatcher.Category keyHit = matcher.classify(entry.getKey());
            if (keyHit != null) {
                return keyHit;
            }
            AttackPatternMatcher.Category valueHit = matcher.classifyAny(entry.getValue());
            if (valueHit != null) {
                return valueHit;
            }
        }

        for (String header : INSPECTED_HEADERS) {
            List<String> values = request.getHeaders().get(header);
            AttackPatternMatcher.Category headerHit = matcher.classifyAny(values);
            if (headerHit != null) {
                return headerHit;
            }
        }
        return null;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
