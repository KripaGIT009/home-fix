package com.homefix.reporting.config;

import java.time.Clock;
import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Core beans for the Reporting Service: the system {@link Clock} used for deterministic download-
 * link expiry (Requirement 20.3) and the dedicated executor that runs long-range asynchronous
 * report generation off the request thread (Requirement 20.3).
 */
@Configuration
public class ReportingConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Bounded pool for background report generation. A queue caps in-flight work so a burst of
     * long-range requests cannot exhaust memory; the caller-runs fallback applies natural
     * backpressure when the queue is full.
     */
    @Bean
    public Executor reportGenerationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("report-gen-");
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
