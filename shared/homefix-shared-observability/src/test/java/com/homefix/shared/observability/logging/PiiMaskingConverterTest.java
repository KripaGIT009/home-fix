package com.homefix.shared.observability.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskingConverterTest {

    private LoggingEvent event(String formattedMessage) {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        LoggingEvent e = new LoggingEvent();
        e.setLoggerContext(ctx);
        e.setLevel(Level.INFO);
        e.setMessage(formattedMessage);
        return e;
    }

    @Test
    void converterScrubsPiiFromRenderedMessage() {
        PiiMaskingConverter converter = new PiiMaskingConverter();
        converter.start();

        String result = converter.convert(event("user email bob@corp.io logged in"));

        assertThat(result).doesNotContain("bob@corp.io");
        assertThat(result).contains(PiiScrubber.MASK);
    }

    @Test
    void converterLeavesCleanMessageUnchanged() {
        PiiMaskingConverter converter = new PiiMaskingConverter();
        converter.start();

        String msg = "dispatch offer sent to provider PRV-77";
        assertThat(converter.convert(event(msg))).isEqualTo(msg);
    }
}
