package com.homefix.complaint.settlement;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link SettlementHoldPort} that logs the hold/release request. A production adapter would
 * call the Booking Service to hold or release the provider settlement (Task 14) over HTTP behind
 * this same port. Activated only when no other {@link SettlementHoldPort} bean is present (tests
 * supply their own).
 */
@Component
public class LoggingSettlementHoldAdapter implements SettlementHoldPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingSettlementHoldAdapter.class);

    @Override
    public void placeHold(UUID bookingId, UUID providerId, UUID complaintId) {
        log.info("SETTLEMENT_HOLD complaint={} placing hold on provider settlement (DISPUTED)",
                complaintId);
    }

    @Override
    public void releaseHold(UUID bookingId, UUID providerId, UUID complaintId) {
        log.info("SETTLEMENT_HOLD complaint={} releasing provider settlement hold (closed)",
                complaintId);
    }
}
