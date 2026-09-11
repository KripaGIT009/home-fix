package com.homefix.auth.domain;

/**
 * Platform roles. A single user account may hold multiple roles simultaneously
 * (e.g. both CUSTOMER and SERVICE_PROVIDER) per Requirement 1.14.
 *
 * <p>Only {@link #CUSTOMER} and {@link #SERVICE_PROVIDER} are self-service: see
 * {@link #isSelfAssignable()}. The staff roles are granted out of band (by an
 * existing administrator, or by the local seeding script) because a caller must
 * never be able to name their own privileged role at registration time.
 *
 * <p>The staff roles below are the ones the rest of the platform already checks
 * for: {@code SUPER_ADMIN} gates System Configuration in admin-service,
 * {@code FINANCE_ADMIN} gates reconciliation and settlement reports in
 * reporting-service, and {@code DISPATCHER} and {@code SUPPORT_AGENT} appear in
 * the requirements as the manual-assignment and complaint-handling actors.
 */
public enum Role {

    /** A customer booking services. Self-service. */
    CUSTOMER(true),

    /** A service professional fulfilling jobs. Self-service. */
    SERVICE_PROVIDER(true),

    /** Operations staff: every admin module except System Configuration. */
    ADMIN(false),

    /** Operations owner: adds System Configuration to the ADMIN surface. */
    SUPER_ADMIN(false),

    /** Finance staff: payment reconciliation and settlement reports. */
    FINANCE_ADMIN(false),

    /** Dispatch desk: manual provider assignment and dispatch tuning. */
    DISPATCHER(false),

    /** Support desk: complaint triage, status changes and refunds. */
    SUPPORT_AGENT(false);

    private final boolean selfAssignable;

    Role(boolean selfAssignable) {
        this.selfAssignable = selfAssignable;
    }

    /**
     * Whether a caller may request this role during public registration.
     *
     * @return true only for CUSTOMER and SERVICE_PROVIDER
     */
    public boolean isSelfAssignable() {
        return selfAssignable;
    }
}
