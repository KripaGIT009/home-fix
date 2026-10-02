package com.homefix.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homefix.notification.domain.NotificationAudience;
import com.homefix.notification.domain.NotificationChannel;
import com.homefix.notification.domain.NotificationContact;
import com.homefix.notification.domain.NotificationEvent;
import com.homefix.notification.domain.NotificationEventType;
import com.homefix.notification.domain.RenderedMessage;
import com.homefix.notification.domain.RenderedMessage.ChannelContent;

/**
 * Each channel port receives that channel's text, so an admin's edit to (say) the SMS wording
 * reaches SMS and nothing else (Requirement 19.2).
 */
class ChannelDispatcherTest {

    @Test
    void eachChannelIsSentItsOwnText() {
        List<String> sent = new ArrayList<>();
        ChannelDispatcher dispatcher = new ChannelDispatcher(
                (mobile, body) -> sent.add("SMS|" + body),
                (address, subject, body) -> sent.add("EMAIL|" + subject + "|" + body),
                (token, title, body) -> sent.add("PUSH|" + title + "|" + body),
                (userId, title, body) -> sent.add("IN_APP|" + title + "|" + body));
        RenderedMessage message = new RenderedMessage(EnumSet.allOf(NotificationChannel.class),
                "Default title", "Default body",
                Map.of(NotificationChannel.SMS, new ChannelContent("Default title", "Short SMS"),
                        NotificationChannel.EMAIL, new ChannelContent("Email subject", "Long email")));
        NotificationEvent event = new NotificationEvent(NotificationEventType.JOB_COMPLETED, UUID.randomUUID(),
                UUID.randomUUID(), NotificationAudience.CUSTOMER,
                new NotificationContact("+911", "a@b.co", "tok"), Map.of());

        for (NotificationChannel channel : NotificationChannel.values()) {
            dispatcher.dispatch(channel, event, message);
        }

        assertThat(sent).containsExactly(
                "PUSH|Default title|Default body",
                "SMS|Short SMS",
                "EMAIL|Email subject|Long email",
                "IN_APP|Default title|Default body");
    }
}
