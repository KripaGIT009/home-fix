package com.homefix.dispatch.config;

import com.homefix.dispatch.adapter.PollingJobOfferAdapter;
import com.homefix.dispatch.adapter.RedisBookingCancellationAdapter;
import com.homefix.dispatch.adapter.RedisDistributedLockAdapter;
import com.homefix.dispatch.adapter.RedisJobOfferStore;
import com.homefix.dispatch.port.BookingCancellationPort;
import com.homefix.dispatch.port.DistributedLockPort;
import com.homefix.dispatch.port.JobOfferPort;
import com.homefix.dispatch.port.JobOfferStore;
import com.homefix.dispatch.port.NotificationPort;
import com.homefix.dispatch.service.JobOfferService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * Registers the outbound adapters that depend on auto-configured infrastructure.
 *
 * <p>The exclusive per-provider offer lock (Requirement 8.11) is what stops the same provider
 * being offered two bookings at once, so the Dispatch Engine must not start without it: this bean
 * takes the auto-configured {@link StringRedisTemplate} as a constructor parameter, and a missing
 * Redis connection therefore fails startup loudly instead of quietly disabling locking.
 *
 * <p>Job offers (Requirements 8.5-8.7) live in the same Redis: the dispatch thread that made an
 * offer and the provider API instance that records the answer need not be the same process.
 */
@Configuration
public class DispatchAdaptersConfig {

    @Bean
    @ConditionalOnMissingBean(DistributedLockPort.class)
    public DistributedLockPort distributedLockPort(StringRedisTemplate redisTemplate) {
        return new RedisDistributedLockAdapter(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock dispatchClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(JobOfferStore.class)
    public JobOfferStore jobOfferStore(StringRedisTemplate redisTemplate) {
        return new RedisJobOfferStore(redisTemplate);
    }

    @Bean
    public JobOfferService jobOfferService(JobOfferStore jobOfferStore, Clock clock) {
        return new JobOfferService(jobOfferStore, clock);
    }

    @Bean
    @ConditionalOnMissingBean(JobOfferPort.class)
    public JobOfferPort jobOfferPort(JobOfferService jobOfferService,
                                     NotificationPort notificationPort,
                                     Clock clock,
                                     DispatchProperties properties) {
        // The provider push is best-effort and must never eat into the offer window, so it runs off
        // the dispatch thread; virtual threads make a per-push thread free.
        return new PollingJobOfferAdapter(jobOfferService, notificationPort,
                Executors.newVirtualThreadPerTaskExecutor(), clock,
                Duration.ofMillis(properties.getOfferPollIntervalMillis()), Thread::sleep);
    }

    @Bean
    @ConditionalOnMissingBean(BookingCancellationPort.class)
    public BookingCancellationPort bookingCancellationPort(StringRedisTemplate redisTemplate) {
        return new RedisBookingCancellationAdapter(redisTemplate);
    }
}
