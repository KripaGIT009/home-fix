package com.homefix.notification.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homefix.notification.consumer.InboundEvent;
import com.homefix.notification.domain.EventParticipants;
import com.homefix.notification.domain.MissingRecipientException;
import com.homefix.notification.domain.NotificationAudience;
import com.homefix.notification.domain.NotificationContact;
import com.homefix.notification.domain.NotificationEvent;
import com.homefix.notification.domain.NotificationEventType;
import com.homefix.notification.domain.RecipientPolicy;
import com.homefix.notification.support.TestDoubles.FakeContactDirectory;

/**
 * Verifies {@link RecipientResolver} addresses one notification per recipient with that
 * recipient's own contact, skips (does not fail) an unknown user, and lets a transient directory
 * failure or a missing recipient propagate for the consumer's retry/dead-letter path.
 */
class RecipientResolverTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final NotificationContact CUSTOMER_CONTACT = new NotificationContact("+911", null, null);
    private static final NotificationContact PROVIDER_CONTACT = new NotificationContact("+912", null, null);

    private static InboundEvent cancelled(UUID customer, UUID provider) {
        return new InboundEvent(NotificationEventType.BOOKING_CANCELLED, UUID.randomUUID(),
                new EventParticipants(customer, provider, null, null), Map.of("bookingReference", "HFX-9"));
    }

    @Test
    void eachRecipientGetsTheirOwnContactAndAudience() {
        FakeContactDirectory directory = new FakeContactDirectory()
                .with(CUSTOMER, CUSTOMER_CONTACT).with(PROVIDER, PROVIDER_CONTACT);
        InboundEvent inbound = cancelled(CUSTOMER, PROVIDER);

        List<NotificationEvent> events = new RecipientResolver(new RecipientPolicy(), directory).resolve(inbound);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).recipientUserId()).isEqualTo(CUSTOMER);
        assertThat(events.get(0).audience()).isEqualTo(NotificationAudience.CUSTOMER);
        assertThat(events.get(0).contact()).isEqualTo(CUSTOMER_CONTACT);
        assertThat(events.get(1).recipientUserId()).isEqualTo(PROVIDER);
        assertThat(events.get(1).audience()).isEqualTo(NotificationAudience.PROVIDER);
        assertThat(events.get(1).contact()).isEqualTo(PROVIDER_CONTACT);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.kafkaEventId()).isEqualTo(inbound.kafkaEventId());
            assertThat(e.attributes()).containsEntry("bookingReference", "HFX-9");
        });
    }

    @Test
    void unknownUser_isAddressedWithEmptyContactInsteadOfFailing() {
        FakeContactDirectory directory = new FakeContactDirectory().with(CUSTOMER, CUSTOMER_CONTACT);

        List<NotificationEvent> events = new RecipientResolver(new RecipientPolicy(), directory)
                .resolve(cancelled(CUSTOMER, PROVIDER));

        assertThat(events).extracting(NotificationEvent::contact)
                .containsExactly(CUSTOMER_CONTACT, NotificationContact.empty());
    }

    @Test
    void directoryOutage_propagatesSoTheConsumerRetries() {
        RecipientResolver resolver = new RecipientResolver(new RecipientPolicy(), FakeContactDirectory.failing());

        assertThatThrownBy(() -> resolver.resolve(cancelled(CUSTOMER, PROVIDER)))
                .isInstanceOf(ContactLookupException.class);
    }

    @Test
    void missingRequiredRecipient_propagatesWithoutConsultingTheDirectory() {
        FakeContactDirectory directory = new FakeContactDirectory();
        RecipientResolver resolver = new RecipientResolver(new RecipientPolicy(), directory);

        assertThatThrownBy(() -> resolver.resolve(cancelled(null, PROVIDER)))
                .isInstanceOf(MissingRecipientException.class);
        assertThat(directory.lookups.get()).isZero();
    }
}
