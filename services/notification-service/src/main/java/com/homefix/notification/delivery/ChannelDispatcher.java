package com.homefix.notification.delivery;

import com.homefix.notification.channel.EmailPort;
import com.homefix.notification.channel.InAppPort;
import com.homefix.notification.channel.NotificationDeliveryException;
import com.homefix.notification.channel.PushPort;
import com.homefix.notification.channel.SmsPort;
import com.homefix.notification.domain.NotificationChannel;
import com.homefix.notification.domain.NotificationContact;
import com.homefix.notification.domain.NotificationEvent;
import com.homefix.notification.domain.RenderedMessage;
import org.springframework.stereotype.Component;

/**
 * Routes a rendered message to the correct channel port. Keeps the port-selection logic out of
 * the orchestrator so {@link NotificationDeliveryService} deals only with the abstract notion of
 * "deliver on channel X".
 *
 * <p>The orchestrator asks {@link #canAddress} first and skips a channel the recipient has no
 * address for, since retrying cannot help. Dispatching to such a channel anyway is still refused
 * with {@link NotificationDeliveryException} as a guard.
 */
@Component
public class ChannelDispatcher {

    private final SmsPort smsPort;
    private final EmailPort emailPort;
    private final PushPort pushPort;
    private final InAppPort inAppPort;

    public ChannelDispatcher(SmsPort smsPort, EmailPort emailPort, PushPort pushPort,
                             InAppPort inAppPort) {
        this.smsPort = smsPort;
        this.emailPort = emailPort;
        this.pushPort = pushPort;
        this.inAppPort = inAppPort;
    }

    /**
     * Whether the recipient has an address on the channel. In-app delivery is addressed by user id
     * and is always possible; SMS, email and push need a phone number, email address and device
     * token respectively.
     */
    public boolean canAddress(NotificationChannel channel, NotificationContact contact) {
        return switch (channel) {
            case SMS -> hasText(contact.mobileNumber());
            case EMAIL -> hasText(contact.emailAddress());
            case PUSH -> hasText(contact.deviceToken());
            case IN_APP -> true;
        };
    }

    /**
     * Dispatches the message on the given channel, with that channel's text (an admin may have
     * edited, say, the SMS wording independently of the email).
     *
     * @throws NotificationDeliveryException if the channel address is missing or the vendor fails
     */
    public void dispatch(NotificationChannel channel, NotificationEvent event, RenderedMessage message) {
        NotificationContact contact = event.contact();
        switch (channel) {
            case SMS -> {
                requireAddress(contact.mobileNumber(), "SMS");
                smsPort.send(contact.mobileNumber(), message.bodyFor(channel));
            }
            case EMAIL -> {
                requireAddress(contact.emailAddress(), "email");
                emailPort.send(contact.emailAddress(), message.titleFor(channel), message.bodyFor(channel));
            }
            case PUSH -> {
                requireAddress(contact.deviceToken(), "push");
                pushPort.send(contact.deviceToken(), message.titleFor(channel), message.bodyFor(channel));
            }
            case IN_APP -> inAppPort.publish(event.recipientUserId(),
                    message.titleFor(channel), message.bodyFor(channel));
        }
    }

    private static boolean hasText(String address) {
        return address != null && !address.isBlank();
    }

    private static void requireAddress(String address, String channelLabel) {
        if (!hasText(address)) {
            throw new NotificationDeliveryException("missing " + channelLabel + " address for recipient");
        }
    }
}
