package com.homefix.booking.client;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Relays the caller's {@code Authorization: Bearer} header to downstream HomeFix services.
 *
 * <p>Downstream services (the Pricing Engine, for example) authenticate every non-public
 * request with the shared {@code JwtValidationFilter}, so a service-to-service call made while
 * handling a customer request must carry that customer's token; otherwise it is rejected as
 * anonymous. Calls made outside a request thread (scheduled work, Kafka consumers) have no
 * token to relay and get {@link Optional#empty()}.
 */
@Component
public class BearerTokenRelay {

    /** @return the inbound {@code Authorization} header value, if this thread is serving a request. */
    public Optional<String> currentAuthorizationHeader() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return Optional.empty();
        }
        HttpServletRequest request = servletAttributes.getRequest();
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return StringUtils.hasText(header) ? Optional.of(header) : Optional.empty();
    }
}
