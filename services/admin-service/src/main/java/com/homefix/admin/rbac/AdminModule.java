package com.homefix.admin.rbac;

/**
 * The 15 operational modules exposed by the Admin Service (Requirement 19.2).
 *
 * <p>Every module is accessible to both ADMIN and SUPER_ADMIN except
 * {@link #SYSTEM_CONFIGURATION}, which is restricted to SUPER_ADMIN (Requirement 19.6, 19.7).
 * The {@code superAdminOnly} flag encodes that distinction so authorization is data-driven
 * rather than scattered across controllers.
 */
public enum AdminModule {

    USER_MANAGEMENT(false),
    PROVIDER_MANAGEMENT(false),
    VERIFICATION_QUEUE(false),
    SERVICE_CATEGORY_MANAGEMENT(false),
    PRICING_CONFIGURATION(false),
    DISPATCH_RULE_CONFIGURATION(false),
    BOOKING_MANAGEMENT(false),
    PAYMENT_AND_REFUND_MANAGEMENT(false),
    COMPLAINT_MANAGEMENT(false),
    REVIEW_MODERATION(false),
    COUPON_MANAGEMENT(false),
    NOTIFICATION_TEMPLATES(false),
    REPORT_GENERATION(false),
    AUDIT_LOGS(false),
    SYSTEM_CONFIGURATION(true);

    private final boolean superAdminOnly;

    AdminModule(boolean superAdminOnly) {
        this.superAdminOnly = superAdminOnly;
    }

    /** {@code true} if only SUPER_ADMIN may access this module (Requirement 19.6). */
    public boolean isSuperAdminOnly() {
        return superAdminOnly;
    }
}
