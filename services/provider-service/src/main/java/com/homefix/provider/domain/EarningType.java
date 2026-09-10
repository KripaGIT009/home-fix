package com.homefix.provider.domain;

/**
 * Ledger entry types for provider earnings history (Requirement 14.1).
 *
 * <p>Only platform fee and complaint-related penalty are permitted deduction types; both are
 * itemised separately from the gross job credit.
 */
public enum EarningType {

    /** Net earning credited from a completed job. */
    JOB_CREDIT,

    /** Platform commission deducted from a job (itemised separately). */
    PLATFORM_FEE_DEDUCTION,

    /** Complaint-related penalty deduction (itemised separately). */
    PENALTY_DEDUCTION
}
