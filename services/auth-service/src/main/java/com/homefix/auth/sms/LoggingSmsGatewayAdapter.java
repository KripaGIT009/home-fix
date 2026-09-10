package com.homefix.auth.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link SmsGatewayPort} adapter used in local/dev profiles.
 *
 * <p>It does not actually contact a carrier - it records that a message would be sent.
 * In production the OTP is PII-sensitive and is never logged. For LOCAL DEVELOPMENT ONLY,
 * setting {@code homefix.sms.log-otp=true} echoes the full message (including the OTP) to
 * the log so the flow can be exercised without a real SMS gateway. This flag defaults to
 * false and must never be enabled outside local/dev.
 *
 * <p>Activated when {@code homefix.sms.provider=log} (the default).
 */
@Component
@ConditionalOnProperty(prefix = "homefix.sms", name = "provider", havingValue = "log", matchIfMissing = true)
public class LoggingSmsGatewayAdapter implements SmsGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsGatewayAdapter.class);

    /** LOCAL DEV ONLY: when true, log the full message body (contains the OTP). */
    @Value("${homefix.sms.log-otp:false}")
    private boolean logOtp;

    @Override
    public void send(String mobileNumber, String message) throws SmsDeliveryException {
        if (logOtp) {
            // Dev convenience only. Guarded by homefix.sms.log-otp, default false.
            log.warn("[DEV SMS] to={} message=\"{}\"", mobileNumber, message);
        } else {
            // Do not log the mobile number (PII) or the message body (contains the OTP).
            log.info("SMS dispatch accepted by logging gateway");
        }
    }
}