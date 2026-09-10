package com.homefix.dispatch.service;

import com.homefix.dispatch.config.BulkheadConfig;
import com.homefix.dispatch.domain.DispatchRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Routes a dispatch job to the correct bulkhead pool (Requirement 24.5): emergency bookings run on
 * the dedicated emergency pool, scheduled bookings on the scheduled pool. Because the pools share
 * nothing, a flood of scheduled bookings cannot consume the threads reserved for emergencies.
 */
@Component
public class BulkheadDispatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(BulkheadDispatchExecutor.class);

    private final DispatchService dispatchService;
    private final ThreadPoolTaskExecutor emergencyExecutor;
    private final ThreadPoolTaskExecutor scheduledExecutor;

    public BulkheadDispatchExecutor(
            DispatchService dispatchService,
            @Qualifier(BulkheadConfig.EMERGENCY_EXECUTOR) ThreadPoolTaskExecutor emergencyExecutor,
            @Qualifier(BulkheadConfig.SCHEDULED_EXECUTOR) ThreadPoolTaskExecutor scheduledExecutor) {
        this.dispatchService = dispatchService;
        this.emergencyExecutor = emergencyExecutor;
        this.scheduledExecutor = scheduledExecutor;
    }

    /** Submits the dispatch of {@code request} to the pool matching its emergency flag. */
    public void submit(DispatchRequest request) {
        ThreadPoolTaskExecutor executor = request.emergency() ? emergencyExecutor : scheduledExecutor;
        executor.execute(() -> runSafely(request));
    }

    private void runSafely(DispatchRequest request) {
        try {
            dispatchService.dispatch(request);
        } catch (RuntimeException ex) {
            // A failure inside a pool task must not kill the worker thread; log and let the
            // consumer's retry/DLQ machinery handle redelivery of the source event.
            log.error("Dispatch failed for booking {}: {}", request.bookingId(), ex.toString());
            throw ex;
        }
    }
}
