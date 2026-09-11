package com.homefix.shared.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the transactional-outbox atomicity guarantee (Task 6, Requirement 22.2): the outbox
 * row and the business state change either both commit or neither does.
 *
 * <p>{@code @DataJpaTest} normally wraps each test in an ambient, roll-back-only transaction.
 * That would mask the behaviour under test — the service's <em>own</em> {@code @Transactional}
 * boundary — so the class disables the ambient transaction with
 * {@link Propagation#NOT_SUPPORTED}. Each test cleans up its own rows afterwards.
 */
@DataJpaTest
// The outbox tables live in a dedicated schema shared by every producer and the relay, so the
// schema has to exist before Hibernate emits the table DDL. A deployment creates it in its init
// script; here Hibernate is told to create namespaces itself. Setting a datasource URL would not
// work: @DataJpaTest replaces the DataSource with its own embedded one.
@TestPropertySource(properties =
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({OutboxWriteAtomicityTest.TestBeans.class})
class OutboxWriteAtomicityTest {

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private AtomicWriteService service;

    private static final UUID AGG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    // With the ambient test transaction disabled, committed rows survive between tests, so each
    // test starts from a clean table.
    @org.junit.jupiter.api.BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void outboxRowCommitsWhenTransactionSucceeds() {
        UUID eventId = service.doWorkAndPublish("BookingCreated", false);

        List<OutboxEventEntity> pending =
                repository.findByStatusOrderByCreatedAtAsc(OutboxEventStatus.PENDING,
                        org.springframework.data.domain.Pageable.unpaged());

        assertThat(pending).hasSize(1);
        OutboxEventEntity row = pending.get(0);
        assertThat(row.getId()).isEqualTo(eventId);
        assertThat(row.getEventType()).isEqualTo("BookingCreated");
        assertThat(row.getAggregateType()).isEqualTo("Booking");
        assertThat(row.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(row.getRetryCount()).isZero();
    }

    @Test
    void outboxRowRolledBackWhenTransactionFails() {
        assertThatThrownBy(() -> service.doWorkAndPublish("BookingCreated", true))
                .isInstanceOf(IllegalStateException.class);

        // The business exception rolled back the whole transaction, so no outbox row survives.
        assertThat(repository.count()).isZero();
    }

    @Autowired
    private OutboxEventPublisher publisher;

    @Test
    void publishOutsideTransactionIsRejected() {
        // MANDATORY propagation on the proxied bean: invoking with no active transaction must fail.
        assertThatThrownBy(() -> publisher.publish("Booking", AGG_ID, "BookingCreated", "{}"))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    /**
     * Simulates a service method that changes business state and writes an outbox row inside a
     * single transaction.
     */
    static class AtomicWriteService {
        private final OutboxEventPublisher publisher;

        AtomicWriteService(OutboxEventPublisher publisher) {
            this.publisher = publisher;
        }

        @Transactional
        UUID doWorkAndPublish(String eventType, boolean failAfterPublish) {
            UUID eventId = publisher.publish("Booking", AGG_ID, eventType,
                    java.util.Map.of("bookingId", AGG_ID.toString()));
            if (failAfterPublish) {
                throw new IllegalStateException("business failure after outbox write");
            }
            return eventId;
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        OutboxEventPublisher outboxEventPublisher(OutboxEventRepository repository, ObjectMapper objectMapper) {
            return new OutboxEventPublisher(repository, objectMapper);
        }

        @Bean
        AtomicWriteService atomicWriteService(OutboxEventPublisher publisher) {
            return new AtomicWriteService(publisher);
        }
    }
}
