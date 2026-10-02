package com.homefix.notification.contact;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.homefix.notification.consumer.InboundEvent;
import com.homefix.notification.domain.NotificationContact;
import com.homefix.notification.domain.NotificationEvent;
import com.homefix.notification.domain.Recipient;
import com.homefix.notification.domain.RecipientPolicy;

/**
 * Turns one consumed event into one addressed {@link NotificationEvent} per recipient: the
 * {@link RecipientPolicy} decides who, and the {@link ContactDirectoryPort} supplies how to reach
 * them (Requirement 17.4).
 *
 * <p>Every recipient's contact is resolved <em>before</em> anything is sent, so a transient
 * lookup failure ({@link ContactLookupException}) aborts the event with nothing delivered and the
 * consumer's retry starts clean. (Even if it did not, the per-recipient delivery log would
 * suppress re-sends.)
 *
 * <p>A user the directory does not know is a logged skip, not an error: they are still addressed
 * with an empty contact, so address-bound channels are recorded as skipped and in-app delivery,
 * which needs no address, still happens. Only the user id is logged, never an address
 * (Requirement 26.4).
 */
@Component
public class RecipientResolver {

    private static final Logger log = LoggerFactory.getLogger(RecipientResolver.class);

    private final RecipientPolicy recipientPolicy;
    private final ContactDirectoryPort contactDirectory;

    public RecipientResolver(RecipientPolicy recipientPolicy, ContactDirectoryPort contactDirectory) {
        this.recipientPolicy = recipientPolicy;
        this.contactDirectory = contactDirectory;
    }

    /**
     * @return one addressed event per recipient, never empty
     * @throws com.homefix.notification.domain.MissingRecipientException if the event lacks the
     *         user id its recipients require (dead-lettered for replay)
     * @throws ContactLookupException if the contact directory could not be consulted (retried)
     */
    public List<NotificationEvent> resolve(InboundEvent event) {
        List<Recipient> recipients = recipientPolicy.recipientsFor(event.eventType(), event.participants());
        List<NotificationEvent> addressed = new ArrayList<>(recipients.size());
        for (Recipient recipient : recipients) {
            NotificationContact contact = contactDirectory.findContact(recipient.userId())
                    .orElseGet(() -> {
                        log.info("No account in the contact directory for user {} ({} of {} event {}); "
                                        + "only in-app delivery is possible",
                                recipient.userId(), recipient.audience(), event.eventType(), event.kafkaEventId());
                        return NotificationContact.empty();
                    });
            addressed.add(new NotificationEvent(event.eventType(), event.kafkaEventId(),
                    recipient.userId(), recipient.audience(), contact, event.attributes()));
        }
        return addressed;
    }
}
