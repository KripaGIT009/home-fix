package com.homefix.shared.resilience;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * Classifies whether a thrown exception represents a <em>transient</em> failure that is eligible
 * for retry with backoff, per Requirement 24.2: connection timeouts, read timeouts, and HTTP 5xx
 * responses.
 *
 * <p>Callers that use {@link org.springframework.web.client.RestClient} should map a 5xx status to
 * a {@link ServerErrorException} (for example via {@code onStatus}) so it is recognised here.
 * Client-side 4xx errors are deliberately <em>not</em> transient and are never retried.
 */
public final class TransientFailures {

    private TransientFailures() {
    }

    /**
     * Returns {@code true} when {@code throwable} (or one of its causes) represents a transient
     * failure eligible for retry: a connection/read timeout or an HTTP 5xx response.
     */
    public static boolean isTransient(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof ServerErrorException
                    || t instanceof SocketTimeoutException
                    || t instanceof HttpTimeoutException
                    || t instanceof TimeoutException
                    || t instanceof java.net.ConnectException) {
                return true;
            }
            if (t instanceof IOException io && isTimeoutMessage(io.getMessage())) {
                return true;
            }
            if (t == t.getCause()) {
                break;
            }
        }
        return false;
    }

    private static boolean isTimeoutMessage(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("timed out") || lower.contains("timeout")
                || lower.contains("connection refused") || lower.contains("connection reset");
    }

    /**
     * Raised by callers when a downstream returns an HTTP 5xx response so it is treated as a
     * transient, retryable failure (Requirement 24.2) and counts against the circuit breaker.
     */
    public static class ServerErrorException extends RuntimeException {

        private final int statusCode;

        public ServerErrorException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
}
