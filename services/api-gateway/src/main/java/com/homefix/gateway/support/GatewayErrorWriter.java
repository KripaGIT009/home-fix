package com.homefix.gateway.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.gateway.filter.CorrelationIdGatewayFilter;
import com.homefix.shared.observability.error.ErrorResponseDto;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Writes the shared {@link ErrorResponseDto} envelope to a reactive response and short-circuits the
 * filter chain.
 *
 * <p>Every rejection carries the request's {@code X-Correlation-ID} (Requirement 23.9) so a client
 * can quote it to support, but never any internal detail (Requirement 23.6, Error-Handling
 * principle 2) — the caller supplies a generic, non-revealing message.
 */
public final class GatewayErrorWriter {

    private final ObjectMapper objectMapper;

    public GatewayErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Writes a JSON error body with the given status and generic error code/message. */
    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String errorCode, String message) {
        return write(exchange, status, errorCode, message, null);
    }

    /**
     * Writes a JSON error body. When {@code retryAfterSeconds} is non-null it is emitted as the
     * {@code Retry-After} header (Requirement 23.5).
     */
    public Mono<Void> write(ServerWebExchange exchange,
                            HttpStatus status,
                            String errorCode,
                            String message,
                            Long retryAfterSeconds) {

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String correlationId = correlationId(exchange);
        if (retryAfterSeconds != null) {
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        }

        ErrorResponseDto dto = ErrorResponseDto.builder()
                .errorCode(errorCode)
                .message(message)
                .correlationId(correlationId)
                .build();

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(dto);
        } catch (Exception e) {
            bytes = ("{\"errorCode\":\"" + errorCode + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private String correlationId(ServerWebExchange exchange) {
        Object attr = exchange.getAttribute(CorrelationIdGatewayFilter.CORRELATION_ID_ATTR);
        if (attr instanceof String s && !s.isBlank()) {
            return s;
        }
        String header = exchange.getRequest().getHeaders()
                .getFirst(CorrelationIdGatewayFilter.CORRELATION_ID_HEADER);
        return header != null ? header : "";
    }
}
