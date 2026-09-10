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
 * <p>A channel whose recipient address is missing is treated as an unrecoverable delivery
 * failure for that channel (it cannot succeed on retry), signalled via
 * {@link NotificationDeliveryException}.
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
     * Dispatches the message on the given channel.
     *
     * @throws NotificationDeliveryException if the channel address is missing or the vendor fails
     */
    public void dispatch(NotificationChannel channel, NotificationEvent event, RenderedMessage message) {
        NotificationContact contact = event.contact();
        switch (channel) {
            case SMS -> {
                requireAddress(contact.mobileNumber(), "SMS");
                smsPort.send(contact.mobileNumber(), message.body());
            }
            case EMAIL -> {
                requireAddress(contact.emailAddress(), "email");
                emailPort.send(contact.emailAddress(), message.title(), message.body());
            }
            case PUSH -> {
                requireAddress(contact.deviceToken(), "push");
                pushPort.send(contact.deviceToken(), message.title(), message.body());
            }
            case IN_APP -> inAppPort.publish(event.recipientUserId(), message.title(), message.body());
        }
    }

    private static void requireAddress(String address, String channelLabel) {
        if (address == null || address.isBlank()) {
            throw new NotificationDeliveryException("missing " + channelLabel + " address for recipient");
        }
    }
}
