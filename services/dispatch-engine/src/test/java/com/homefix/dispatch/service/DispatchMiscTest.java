package com.homefix.dispatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.homefix.dispatch.config.BulkheadConfig;
import com.homefix.dispatch.config.DispatchBulkheadProperties;
import com.homefix.dispatch.config.DispatchClientProperties;
import com.homefix.dispatch.config.DispatchProperties;
import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.event.ProviderAcceptedEvent;
import com.homefix.dispatch.event.ProviderRejectedEvent;
import com.homefix.shared.outbox.OutboxEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Unit tests for the dispatch support classes: bulkhead routing by emergency flag (Requirement
 * 24.5), the ProviderAccepted outbox publisher seam (Requirement 8.6), and the tunable client and
 * dispatch properties.
 */
class DispatchMiscTest {

    private DispatchRequest request(boolean emergency) {
        return new DispatchRequest(UUID.randomUUID(), UUID.randomUUID(), 12.9, 77.6,
                UUID.randomUUID(), List.of("plumbing"), emergency, Instant.now());
    }

    @Test
    void bulkheadExecutor_routesEmergencyToEmergencyPoolAndScheduledToScheduledPool()
            throws Exception {
        DispatchService dispatchService = mock(DispatchService.class);
        BulkheadConfig config = new BulkheadConfig();
        DispatchBulkheadProperties props = new DispatchBulkheadProperties();
        ThreadPoolTaskExecutor emergency = config.emergencyDispatchExecutor(props);
        ThreadPoolTaskExecutor scheduled = config.scheduledDispatchExecutor(props);
        emergency.initialize();
        scheduled.initialize();

        BulkheadDispatchExecutor executor =
                new BulkheadDispatchExecutor(dispatchService, emergency, scheduled);

        DispatchRequest emergencyReq = request(true);
        DispatchRequest scheduledReq = request(false);
        executor.submit(emergencyReq);
        executor.submit(scheduledReq);

        // Let the pool tasks run.
        emergency.getThreadPoolExecutor().shutdown();
        scheduled.getThreadPoolExecutor().shutdown();
        emergency.getThreadPoolExecutor().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        scheduled.getThreadPoolExecutor().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        verify(dispatchService).dispatch(emergencyReq);
        verify(dispatchService).dispatch(scheduledReq);
    }

    @Test
    void bulkheadExecutor_taskFailureIsRethrownInsidePool() throws Exception {
        DispatchService dispatchService = mock(DispatchService.class);
        DispatchRequest req = request(false);
        doThrow(new RuntimeException("boom")).when(dispatchService).dispatch(any());

        BulkheadConfig config = new BulkheadConfig();
        DispatchBulkheadProperties props = new DispatchBulkheadProperties();
        ThreadPoolTaskExecutor scheduled = config.scheduledDispatchExecutor(props);
        ThreadPoolTaskExecutor emergency = config.emergencyDispatchExecutor(props);
        scheduled.initialize();
        emergency.initialize();

        BulkheadDispatchExecutor executor =
                new BulkheadDispatchExecutor(dispatchService, emergency, scheduled);
        executor.submit(req);

        scheduled.getThreadPoolExecutor().shutdown();
        scheduled.getThreadPoolExecutor().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        verify(dispatchService).dispatch(req);
    }

    @Test
    void providerAcceptedPublisher_publishesEventThroughOutbox() {
        OutboxEventPublisher outbox = mock(OutboxEventPublisher.class);
        ProviderAcceptedPublisher publisher = new ProviderAcceptedPublisher(outbox);
        UUID booking = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID provider = UUID.randomUUID();

        publisher.publish(booking, customer, provider, Instant.now());

        verify(outbox).publish(eq(ProviderAcceptedEvent.AGGREGATE_TYPE), eq(booking),
                eq(ProviderAcceptedEvent.EVENT_TYPE), any(ProviderAcceptedEvent.class));
    }

    @Test
    void providerRejectedPublisher_publishesEventThroughOutboxWithConsumerFieldNames() {
        OutboxEventPublisher outbox = mock(OutboxEventPublisher.class);
        ProviderRejectedPublisher publisher = new ProviderRejectedPublisher(outbox);
        UUID booking = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID provider = UUID.randomUUID();

        publisher.publish(booking, customer, provider, ProviderRejectedEvent.REASON_TIMED_OUT);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(eq(ProviderRejectedEvent.AGGREGATE_TYPE), eq(booking),
                eq(ProviderRejectedEvent.EVENT_TYPE), payload.capture());
        ProviderRejectedEvent event = (ProviderRejectedEvent) payload.getValue();
        assertThat(event.bookingId()).isEqualTo(booking);
        assertThat(event.customerId()).isEqualTo(customer);
        assertThat(event.providerId()).isEqualTo(provider);
        assertThat(event.reason()).isEqualTo("TIMED_OUT");
        assertThat(event.rejectedAt()).isNotNull();
        // The relay maps this event type to the topic the Notification Service listens on.
        assertThat(ProviderRejectedEvent.EVENT_TYPE).isEqualTo("ProviderRejected");
    }

    @Test
    void dispatchClientProperties_roundTrip() {
        DispatchClientProperties p = new DispatchClientProperties();
        assertThat(p.getProviderServiceBaseUrl()).isEqualTo("http://provider-service");
        assertThat(p.getBookingServiceBaseUrl()).isEqualTo("http://booking-service");
        assertThat(p.getNotificationServiceBaseUrl()).isEqualTo("http://notification-service");
        assertThat(p.getCustomerServiceBaseUrl()).isEqualTo("http://customer-service");
        assertThat(p.getCatalogServiceBaseUrl()).isEqualTo("http://catalog-service");
        p.setCustomerServiceBaseUrl("d");
        p.setCatalogServiceBaseUrl("e");
        assertThat(p.getCustomerServiceBaseUrl()).isEqualTo("d");
        assertThat(p.getCatalogServiceBaseUrl()).isEqualTo("e");
        p.setProviderServiceBaseUrl("a");
        p.setBookingServiceBaseUrl("b");
        p.setNotificationServiceBaseUrl("c");
        assertThat(p.getProviderServiceBaseUrl()).isEqualTo("a");
        assertThat(p.getBookingServiceBaseUrl()).isEqualTo("b");
        assertThat(p.getNotificationServiceBaseUrl()).isEqualTo("c");
    }

    @Test
    void dispatchProperties_exposeDefaultsAndRoundTrip() {
        DispatchProperties p = new DispatchProperties();
        assertThat(p.getInitialRadiusKm()).isEqualTo(10.0);
        assertThat(p.getRadiusIncrementKm()).isEqualTo(5.0);
        assertThat(p.getMaxExpansionCycles()).isEqualTo(3);
        assertThat(p.getOfferTimeoutSeconds()).isEqualTo(60L);
        p.setInitialRadiusKm(8.0);
        p.setRadiusIncrementKm(4.0);
        p.setMaxExpansionCycles(5);
        p.setOfferTimeoutSeconds(90L);
        assertThat(p.getInitialRadiusKm()).isEqualTo(8.0);
        assertThat(p.getRadiusIncrementKm()).isEqualTo(4.0);
        assertThat(p.getMaxExpansionCycles()).isEqualTo(5);
        assertThat(p.getOfferTimeoutSeconds()).isEqualTo(90L);
    }

    @Test
    void bulkheadProperties_roundTrip() {
        DispatchBulkheadProperties p = new DispatchBulkheadProperties();
        p.setEmergencyCoreThreads(2);
        p.setEmergencyMaxThreads(6);
        assertThat(p.getEmergencyCoreThreads()).isEqualTo(2);
        assertThat(p.getEmergencyMaxThreads()).isEqualTo(6);
        assertThat(p.getEmergencyQueueCapacity()).isEqualTo(100);
        p.setScheduledCoreThreads(3);
        p.setScheduledMaxThreads(7);
        p.setScheduledQueueCapacity(200);
        assertThat(p.getScheduledCoreThreads()).isEqualTo(3);
        assertThat(p.getScheduledMaxThreads()).isEqualTo(7);
        assertThat(p.getScheduledQueueCapacity()).isEqualTo(200);
    }
}
