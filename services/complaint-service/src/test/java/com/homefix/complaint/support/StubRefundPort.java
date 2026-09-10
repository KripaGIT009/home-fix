package com.homefix.complaint.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.homefix.complaint.payment.RefundPort;
import com.homefix.complaint.payment.RefundResult;

/**
 * Configurable {@link RefundPort} test double. Returns a preset {@link RefundResult} (approved or
 * rejected) so both the happy-path (16.5) and the REFUND_FAILED flow (16.6) can be exercised, and
 * records each request for assertion.
 */
public class StubRefundPort implements RefundPort {

    private RefundResult nextResult;
    private final List<BigDecimal> requests = new ArrayList<>();

    public StubRefundPort(RefundResult nextResult) {
        this.nextResult = nextResult;
    }

    public static StubRefundPort approving() {
        return new StubRefundPort(RefundResult.approved("stub_rf_ref"));
    }

    public static StubRefundPort rejecting(String reason) {
        return new StubRefundPort(RefundResult.rejected(reason));
    }

    public void setNextResult(RefundResult nextResult) {
        this.nextResult = nextResult;
    }

    @Override
    public RefundResult requestRefund(UUID bookingId, UUID complaintId, BigDecimal amount) {
        requests.add(amount);
        return nextResult;
    }

    public List<BigDecimal> requests() {
        return requests;
    }
}
