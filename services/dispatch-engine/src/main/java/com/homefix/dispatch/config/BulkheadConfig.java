package com.homefix.dispatch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bulkhead thread pools (Requirement 24.5). Emergency dispatch runs on a dedicated, non-shared
 * pool so that exhaustion of the scheduled-dispatch pool can never starve emergency dispatch of
 * threads. The two pools share no queues, threads, or rejection handlers.
 *
 * <p>Each pool uses a bounded queue and an abort policy: when a pool is saturated, its own tasks
 * are rejected rather than spilling over into the other pool — that spill-over is exactly what the
 * bulkhead exists to prevent.
 */
@Configuration
public class BulkheadConfig {

    /** Bean name for the emergency dispatch pool. */
    public static final String EMERGENCY_EXECUTOR = "emergencyDispatchExecutor";
    /** Bean name for the scheduled dispatch pool. */
    public static final String SCHEDULED_EXECUTOR = "scheduledDispatchExecutor";

    @Bean(name = EMERGENCY_EXECUTOR)
    public ThreadPoolTaskExecutor emergencyDispatchExecutor(DispatchBulkheadProperties props) {
        return pool("emergency-dispatch-",
                props.getEmergencyCoreThreads(),
                props.getEmergencyMaxThreads(),
                props.getEmergencyQueueCapacity());
    }

    @Bean(name = SCHEDULED_EXECUTOR)
    public ThreadPoolTaskExecutor scheduledDispatchExecutor(DispatchBulkheadProperties props) {
        return pool("scheduled-dispatch-",
                props.getScheduledCoreThreads(),
                props.getScheduledMaxThreads(),
                props.getScheduledQueueCapacity());
    }

    private static ThreadPoolTaskExecutor pool(String prefix, int core, int max, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        // Reject on saturation rather than borrowing from the sibling pool (the bulkhead invariant).
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setAllowCoreThreadTimeOut(false);
        executor.initialize();
        return executor;
    }
}
