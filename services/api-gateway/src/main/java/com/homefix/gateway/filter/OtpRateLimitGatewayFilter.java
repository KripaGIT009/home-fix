package com.homefix.gateway.filter;

import com.homefix.gateway.config.GatewaySecurityProperties;
import com.homefix.gateway.ratelimit.RateLimitCounter;
import com.homefix.gateway.support.GatewayErrorWriter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Enforces per-phone-number rate limiting on OTP generation endpoints: at most 5 requests per phone
 * per hour (Requirement 23.4).
 *
 * <p>Only requests whose path matches the configured OTP path are throttled. The phone number is
 * read from the {@code X-Phone-Number} header or a {@code mobileNumber}/{@code phone} query
 * parameter. (The AWS API Gateway edge extracts it from the JSON body for the production managed
 * throttle; this application-layer filter mirrors the control so it is unit-testable and enforced
 * defence-in-depth.) The phone number is hashed into an opaque counter key so it is never logged or
 * stored in clear (Requirement 26.4).
 *
 * <p>On breach the request is rejected with {@code 429 Too Many Requests} and a {@code Retry-After}
 * header set to one hour.
 */
@Component
public class OtpRateLimitGatewayFilter implements GlobalFilter, Ordered {

    /** Runs before the general per-user limiter and after WAF. */
    public static final int ORDER = -60;

    private static final Duration WINDOW = Duration.ofHours(1);
    private static final long WINDOW_SECONDS = WINDOW.toSeconds();

    private final GatewaySecurityProperties properties;
    private final RateLimitCounter counter;
    private final GatewayErrorWriter errorWriter;

    public OtpRateLimitGatewayFilter(GatewaySecurityProperties properties,
                                     RateLimitCounter counter,
                                     GatewayErrorWriter errorWriter) {
        this.properties = properties;
        this.counter = counter;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith(properties.getOtp().getOtpPath())) {
            return chain.filter(exchange);
        }

        String phone = resolvePhone(exchange);
        if (phone == null) {
            // No phone to key on — defer to downstream validation, do not throttle blindly.
            return chain.filter(exchange);
        }

        int max = properties.getOtp().getMaxPerPhonePerHour();
        String key = otpKey(phone);

        return counter.incrementAndGet(key, WINDOW)
                .flatMap(count -> {
                    if (count > max) {
                        return errorWriter.write(exchange, HttpStatus.TOO_MANY_REQUESTS,
                                "OTP_RATE_LIMIT_EXCEEDED",
                                "Too many OTP requests for this number. Try again later.",
                                WINDOW_SECONDS);
                    }
                    return chain.filter(exchange);
                });
    }

    static String resolvePhone(ServerWebExchange exchange) {
        var request = exchange.getRequest();
        String header = request.getHeaders().getFirst("X-Phone-Number");
        if (StringUtils.hasText(header)) {
            return header.trim();
        }
        String param = request.getQueryParams().getFirst("mobileNumber");
        if (!StringUtils.hasText(param)) {
            param = request.getQueryParams().getFirst("phone");
        }
        return StringUtils.hasText(param) ? param.trim() : null;
    }

    /** Opaque, non-reversible counter key derived from the phone number (no PII in Redis/logs). */
    static String otpKey(String phone) {
        return "gw:ratelimit:otp:" + Integer.toHexString(phone.hashCode());
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
