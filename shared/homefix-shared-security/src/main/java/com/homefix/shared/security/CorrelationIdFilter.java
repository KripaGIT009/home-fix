package com.homefix.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * OncePerRequestFilter that guarantees every request carries an
 * {@code X-Correlation-ID}.
 *
 * <ul>
 *   <li>If the inbound request already carries an {@code X-Correlation-ID} header,
 *       that value is reused so a single correlation ID flows across service hops.</li>
 *   <li>Otherwise a fresh UUID v4 is generated.</li>
 *   <li>The value is placed into the SLF4J {@link MDC} under {@link #MDC_KEY} so it
 *       appears in structured logs, and echoed back on the response header.</li>
 *   <li>The MDC entry is always removed in a {@code finally} block to prevent the
 *       value leaking across pooled request threads.</li>
 * </ul>
 *
 * <p>This filter is ordered to run first (before {@link JwtValidationFilter}) so the
 * correlation ID is available for every subsequent log line.
 */
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    /** HTTP header name used to carry the correlation ID across service boundaries. */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    /** SLF4J MDC key under which the correlation ID is exposed to log appenders. */
    public static final String MDC_KEY = "correlationId";

    private final int order;

    public CorrelationIdFilter() {
        this(Ordered.HIGHEST_PRECEDENCE);
    }

    public CorrelationIdFilter(int order) {
        this.order = order;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String correlationId = resolveCorrelationId(request);
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Returns the inbound correlation ID if present and non-blank, otherwise a new
     * UUID v4 string.
     */
    private String resolveCorrelationId(HttpServletRequest request) {
        String inbound = request.getHeader(CORRELATION_ID_HEADER);
        if (StringUtils.hasText(inbound)) {
            return inbound.trim();
        }
        return UUID.randomUUID().toString();
    }

    @Override
    public int getOrder() {
        return order;
    }
}
