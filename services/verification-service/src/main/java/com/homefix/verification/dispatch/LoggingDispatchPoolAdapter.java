package com.homefix.verification.dispatch;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link DispatchPoolPort} that logs the deactivation signal. A production adapter can
 * publish a {@code ProviderSuspended} event to Kafka via the shared outbox so the Dispatch
 * Engine removes the provider from the active pool and cancels pending offers, without
 * touching the verification workflow.
 */
@Component
public class LoggingDispatchPoolAdapter implements DispatchPoolPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingDispatchPoolAdapter.class);

    @Override
    public void deactivateProvider(UUID providerId) {
        log.info("Removing provider={} from active dispatch pool and cancelling pending offers",
                providerId);
    }
}
