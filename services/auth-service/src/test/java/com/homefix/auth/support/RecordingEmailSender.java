package com.homefix.auth.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.homefix.auth.email.EmailDeliveryException;
import com.homefix.auth.email.EmailMessage;
import com.homefix.auth.email.EmailSenderPort;

/** Test double for {@link EmailSenderPort}: keeps every email and can be made to fail. */
public class RecordingEmailSender implements EmailSenderPort {

    public record Sent(String to, EmailMessage message) {
    }

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");
    private static final Pattern INVITE_TOKEN = Pattern.compile("/invite/([A-Za-z0-9_-]+)");

    private final List<Sent> sent = new ArrayList<>();
    private boolean fail;

    public void failDelivery() {
        this.fail = true;
    }

    public List<Sent> sent() {
        return sent;
    }

    public Sent last() {
        if (sent.isEmpty()) {
            throw new AssertionError("No email was sent");
        }
        return sent.get(sent.size() - 1);
    }

    /** The 6-digit code in the last email's body. */
    public String lastCode() {
        Matcher matcher = CODE.matcher(last().message().body());
        if (!matcher.find()) {
            throw new AssertionError("The last email holds no code: " + last().message().subject());
        }
        return matcher.group(1);
    }

    /** The token in the last invitation link. */
    public String lastInvitationToken() {
        Matcher matcher = INVITE_TOKEN.matcher(last().message().body());
        if (!matcher.find()) {
            throw new AssertionError("The last email holds no invitation link");
        }
        return matcher.group(1);
    }

    @Override
    public void send(String to, EmailMessage message) {
        if (fail) {
            throw new EmailDeliveryException("simulated failure", null);
        }
        sent.add(new Sent(to, message));
    }
}
