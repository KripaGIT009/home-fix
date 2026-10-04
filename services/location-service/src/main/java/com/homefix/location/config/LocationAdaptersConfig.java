package com.homefix.location.config;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.homefix.location.booking.BookingParticipantsPort;
import com.homefix.location.booking.HttpBookingParticipantsAdapter;
import com.homefix.location.domain.Coordinates;
import com.homefix.location.eta.EtaCalculatorPort;
import com.homefix.location.eta.HaversineEtaCalculatorAdapter;
import com.homefix.location.store.LocationStorePort;
import com.homefix.location.store.RedisLocationStoreAdapter;
import com.homefix.location.subscription.InMemorySubscriberRegistry;
import com.homefix.location.subscription.SubscriberRegistryPort;
import com.homefix.shared.resilience.ResilienceFactory;

/**
 * Wires the mockable Location Service abstractions to their runtime adapters (Requirement 10).
 *
 * <ul>
 *   <li>{@link LocationStorePort} → Redis adapter by default; disable with
 *       {@code homefix.location.store=memory} (tests supply their own fake).</li>
 *   <li>{@link SubscriberRegistryPort} → in-memory concurrent registry.</li>
 *   <li>{@link EtaCalculatorPort} → haversine estimator over a destination resolver.</li>
 *   <li>{@link BookingParticipantsPort} → the Booking Service's internal payment-facts endpoint,
 *       with the shared {@code INTERNAL_API_KEY}; used for the ownership checks.</li>
 * </ul>
 *
 * <p>Each bean is {@code @ConditionalOnMissingBean} so tests (and future production adapters)
 * can override any single collaborator without replacing the whole configuration.
 */
@Configuration
public class LocationAdaptersConfig {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "homefix.location", name = "store", havingValue = "redis",
            matchIfMissing = true)
    public LocationStorePort locationStore(StringRedisTemplate redisTemplate,
                                           ObjectMapper objectMapper,
                                           LocationProperties properties) {
        return new RedisLocationStoreAdapter(redisTemplate, objectMapper, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public BookingParticipantsPort bookingParticipants(
            ResilienceFactory resilienceFactory,
            @Value("${homefix.location.booking-service-url:http://booking-service:8084}") String baseUrl,
            @Value("${homefix.location.internal-api-key:}") String internalApiKey) {
        return new HttpBookingParticipantsAdapter(resilienceFactory, baseUrl, internalApiKey);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubscriberRegistryPort subscriberRegistry() {
        return new InMemorySubscriberRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public EtaCalculatorPort etaCalculator(HaversineEtaCalculatorAdapter.DestinationResolver resolver) {
        // 30 km/h assumed average urban travel speed until a Maps provider is wired in.
        return new HaversineEtaCalculatorAdapter(resolver, 30.0);
    }

    /**
     * Placeholder destination resolver until the Booking Service address lookup is integrated.
     * Returns a fixed reference point so the service is runnable end-to-end in dev; production
     * replaces this bean with a Booking Service client.
     */
    @Bean
    @ConditionalOnMissingBean
    public HaversineEtaCalculatorAdapter.DestinationResolver destinationResolver() {
        return (UUID bookingId) -> new Coordinates(0.0, 0.0);
    }
}
