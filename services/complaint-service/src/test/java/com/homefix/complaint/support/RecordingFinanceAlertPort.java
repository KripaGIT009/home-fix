package com.homefix.complaint.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.homefix.complaint.alert.FinanceAlertPort;

/**
 * Recording {@link FinanceAlertPort} test double: captures each Finance_Admin alert so the
 * refund-rejection alert (16.6) can be asserted.
 */
public class RecordingFinanceAlertPort implements FinanceAlertPort {

    private final List<UUID> alerts = new ArrayList<>();

    @Override
    public void refundRequiresManualProcessing(UUID complaintId, UUID bookingId, BigDecimal amount,
                                                String reason) {
        alerts.add(complaintId);
    }

    public List<UUID> alerts() {
        return alerts;
    }
}
