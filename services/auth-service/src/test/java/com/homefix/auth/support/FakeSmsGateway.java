package com.homefix.auth.support;

import java.util.ArrayList;
import java.util.List;

import com.homefix.auth.sms.SmsDeliveryException;
import com.homefix.auth.sms.SmsGatewayPort;

/**
 * Test double for {@link SmsGatewayPort}. Captures sent messages and can be switched to
 * simulate a delivery failure (Requirement 1.16).
 */
public class FakeSmsGateway implements SmsGatewayPort {

    public record Sent(String mobileNumber, String message) {
    }

    private final List<Sent> sent = new ArrayList<>();
    private boolean failNext = false;

    /** Simulate an undeliverable/invalid number on the next (and subsequent) send. */
    public void failDelivery() {
        this.failNext = true;
    }

    public List<Sent> sentMessages() {
        return sent;
    }

    @Override
    public void send(String mobileNumber, String message) throws SmsDeliveryException {
        if (failNext) {
            throw new SmsDeliveryException("simulated undeliverable number");
        }
        sent.add(new Sent(mobileNumber, message));
    }

    /**
     * Extracts the OTP code embedded in the last sent message (tests need the raw code
     * because the service only stores the hash).
     */
    public String lastOtpCode() {
        if (sent.isEmpty()) {
            throw new IllegalStateException("no SMS sent");
        }
        String message = sent.get(sent.size() - 1).message();
        // "Your HomeFix verification code is 123456. ..."
        return message.replaceAll("\\D*(\\d{4,10}).*", "$1");
    }
}
