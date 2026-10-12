package com.homefix.auth.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link EmailSenderPort} adapter: records that an email was accepted, by subject only.
 * It delivers nothing. The recipient is PII and the body may hold a code or an invitation link, so
 * neither is logged (Requirement 7.2, platform Requirement 26.4).
 *
 * <p>Active when {@code homefix.email.provider} is {@code logging} or unset.
 */
@Component
@ConditionalOnProperty(prefix = "homefix.email", name = "provider", havingValue = "logging", matchIfMissing = true)
public class LoggingEmailSenderAdapter implements EmailSenderPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSenderAdapter.class);

    @Override
    public void send(String to, EmailMessage message) {
        log.info("Email \"{}\" accepted by the logging email gateway (not delivered)", message.subject());
    }
}
