package com.homefix.outbox.relay;

/**
 * Seam over {@link Thread#sleep(long)} so the exponential-backoff waits in {@link OutboxRelayService}
 * can be exercised instantly (and asserted) in unit tests. Production wiring supplies a real
 * sleeping implementation; tests supply a no-op or recording double.
 */
@FunctionalInterface
public interface Sleeper {

    /**
     * Pauses the current thread for the given number of milliseconds.
     *
     * @param millis milliseconds to sleep
     * @throws InterruptedException if the thread is interrupted while sleeping
     */
    void sleep(long millis) throws InterruptedException;
}
