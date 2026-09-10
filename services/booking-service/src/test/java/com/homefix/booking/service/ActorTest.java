package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Actor}: human actors carry a non-null id and role; the system actor
 * carries a null id and the service name (Requirement 9.15).
 */
class ActorTest {

    @Test
    void userActorCarriesIdAndRole() {
        UUID id = UUID.randomUUID();
        Actor actor = Actor.user(id, "CUSTOMER");

        assertThat(actor.id()).isEqualTo(id);
        assertThat(actor.role()).isEqualTo("CUSTOMER");
    }

    @Test
    void systemActorHasNullIdAndServiceRole() {
        Actor actor = Actor.system();

        assertThat(actor.id()).isNull();
        assertThat(actor.role()).isEqualTo("booking-service");
    }

    @Test
    void userActorRejectsNullId() {
        assertThatThrownBy(() -> Actor.user(null, "CUSTOMER"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actorRejectsBlankRole() {
        assertThatThrownBy(() -> new Actor(UUID.randomUUID(), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
