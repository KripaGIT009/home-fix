package com.homefix.admin.audit;

/**
 * The categories of Admin action that must be recorded in the Audit_Log (Requirement 19.8).
 *
 * <ul>
 *   <li>{@link #CREATE} — records the initial field values as {@code afterValues}.</li>
 *   <li>{@link #UPDATE} — records the before and after values of each modified field.</li>
 *   <li>{@link #DELETE} — records the field values at time of deletion as {@code beforeValues}.</li>
 *   <li>{@link #APPROVE}/{@link #REJECT} — moderation-style transitions (before/after state).</li>
 * </ul>
 */
public enum AdminAction {
    CREATE,
    UPDATE,
    DELETE,
    APPROVE,
    REJECT
}
