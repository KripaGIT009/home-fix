package com.homefix.verification.backgroundcheck;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link BackgroundCheckPort} that logs the initiation and returns a synthetic vendor
 * reference. A production adapter can call the real background-check vendor without touching
 * the verification workflow.
 */
@Component
public class LoggingBackgroundCheckAdapter implements BackgroundCheckPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingBackgroundCheckAdapter.class);

    @Override
    public String initiate(UUID providerId) {
        String reference = "BGC-" + UUID.randomUUID();
        log.info("Initiated background check provider={} reference={}", providerId, reference);
        return reference;
    }
}
