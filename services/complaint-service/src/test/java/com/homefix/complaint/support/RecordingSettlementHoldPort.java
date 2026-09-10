package com.homefix.complaint.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.homefix.complaint.settlement.SettlementHoldPort;

/**
 * Recording {@link SettlementHoldPort} test double: captures each hold/release so the DISPUTED hold
 * placement (16.7) and closure release (16.8) can be asserted.
 */
public class RecordingSettlementHoldPort implements SettlementHoldPort {

    private final List<UUID> holds = new ArrayList<>();
    private final List<UUID> releases = new ArrayList<>();

    @Override
    public void placeHold(UUID bookingId, UUID providerId, UUID complaintId) {
        holds.add(complaintId);
    }

    @Override
    public void releaseHold(UUID bookingId, UUID providerId, UUID complaintId) {
        releases.add(complaintId);
    }

    public List<UUID> holds() {
        return holds;
    }

    public List<UUID> releases() {
        return releases;
    }
}
