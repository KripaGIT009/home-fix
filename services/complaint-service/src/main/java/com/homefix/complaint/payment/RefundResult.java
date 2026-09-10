package com.homefix.complaint.payment;

/**
 * Outcome of a refund request submitted to the Payment Service (Requirement 16.5, 16.6).
 *
 * @param approved       whether the Payment Service accepted and processed the refund
 * @param transactionRef the Payment Service transaction/refund reference when approved (may be null)
 * @param failureReason  a human-readable reason when the refund was rejected (may be null)
 */
public record RefundResult(boolean approved, String transactionRef, String failureReason) {

    public static RefundResult approved(String transactionRef) {
        return new RefundResult(true, transactionRef, null);
    }

    public static RefundResult rejected(String failureReason) {
        return new RefundResult(false, null, failureReason);
    }
}
