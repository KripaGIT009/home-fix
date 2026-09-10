package com.homefix.shared.resilience;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a synchronous outbound call to a named dependency under the full HomeFix resilience
 * stack (Requirement 24): a per-call timeout ({@link TimeoutProfile}), retry with exponential
 * backoff for transient failures, and a circuit breaker. When the breaker is open — or when every
 * attempt has been exhausted — the configured {@link FallbackDecision} produces a degraded
 * response and a WARN log naming the failed dependency is emitted (Requirement 24.4).
 *
 * <p>Decoration order (innermost first): {@code timeout → retry → circuit breaker}. The timeout is
 * innermost so each individual attempt is bounded; the retry then re-issues timed-out or 5xx
 * attempts; the breaker observes the final outcome of the retried call and trips when the failure
 * rate crosses the threshold.
 *
 * <p>Instances are cheap and thread-safe; create one per dependency and reuse it.
 *
 * @param <T> the type returned by the guarded call
 */
public final class ResilientCall<T> {

    private static final Logger log = LoggerFactory.getLogger(ResilientCall.class);

    // A small shared pool used only to bound each attempt with a hard timeout.
    private static final ExecutorService TIMEOUT_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "resilient-call-timeout");
        t.setDaemon(true);
        return t;
    });

    private final String dependencyName;
    private final java.time.Duration timeout;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final FallbackDecision<T> fallback;

    private ResilientCall(String dependencyName, java.time.Duration timeout,
                          CircuitBreaker circuitBreaker, Retry retry, FallbackDecision<T> fallback) {
        this.dependencyName = dependencyName;
        this.timeout = timeout;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.fallback = fallback;
    }

    /**
     * Creates a resilient call for {@code dependencyName} using the given factory and timeout
     * profile. The {@code fallback} supplies the degraded value returned when the breaker is open
     * or all attempts fail.
     */
    public static <T> ResilientCall<T> forDependency(ResilienceFactory factory, String dependencyName,
                                                     TimeoutProfile timeoutProfile,
                                                     FallbackDecision<T> fallback) {
        return forDependency(factory, dependencyName, timeoutProfile.timeout(), fallback);
    }

    /**
     * Creates a resilient call with an explicit per-attempt timeout. Prefer the
     * {@link TimeoutProfile} overload in production code; this exists for callers that need a
     * bespoke ceiling and for deterministic tests.
     */
    public static <T> ResilientCall<T> forDependency(ResilienceFactory factory, String dependencyName,
                                                     java.time.Duration timeout,
                                                     FallbackDecision<T> fallback) {
        return new ResilientCall<>(dependencyName, timeout,
                factory.circuitBreaker(dependencyName), factory.retry(dependencyName), fallback);
    }

    /**
     * Runs {@code action} under the resilience stack. On success returns its value; on breaker-open
     * or exhausted-retry failure returns the fallback value and logs a WARN naming the dependency.
     */
    public T execute(Callable<T> action) {
        Supplier<T> timed = () -> callWithTimeout(action);
        Supplier<T> retrying = Retry.decorateSupplier(retry, timed);
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(circuitBreaker, retrying);
        try {
            return guarded.get();
        } catch (CallNotPermittedException open) {
            // Breaker is open: fail fast to a degraded response without touching the dependency.
            log.warn("Circuit breaker OPEN for dependency '{}'; returning degraded fallback response",
                    dependencyName);
            return fallback.apply(open);
        } catch (Exception failure) {
            // All retries exhausted (or a non-transient error): degrade and name the dependency.
            log.warn("Call to dependency '{}' failed after retries; returning degraded fallback response",
                    dependencyName);
            return fallback.apply(failure);
        }
    }

    private T callWithTimeout(Callable<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        var task = TIMEOUT_POOL.submit(() -> {
            try {
                future.complete(action.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            task.cancel(true);
            // Surface as a transient failure so retry/breaker treat it consistently (Req 24.2/24.3).
            throw new TransientFailures.ServerErrorException(504,
                    "Call to '" + dependencyName + "' exceeded " + timeout);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted calling '" + dependencyName + "'", ie);
        }
    }

    public String dependencyName() {
        return dependencyName;
    }

    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }
}
