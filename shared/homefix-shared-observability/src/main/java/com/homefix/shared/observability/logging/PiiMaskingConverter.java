package com.homefix.shared.observability.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback converter that runs every formatted log message through
 * {@link PiiScrubber} before it reaches any appender.
 *
 * <p>Registered under the conversion word {@code piiMessage} in the shared
 * logback configuration, and also wired into the JSON encoder so the
 * {@code message} field of structured logs is scrubbed of PII (Requirement 26.4).
 */
public class PiiMaskingConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        String formatted = super.convert(event);
        return PiiScrubber.scrub(formatted);
    }
}
