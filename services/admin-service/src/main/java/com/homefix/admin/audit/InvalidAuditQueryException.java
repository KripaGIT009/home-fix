package com.homefix.admin.audit;

/** An Audit_Log listing query has an unknown action or a malformed cursor (-> 400). */
public class InvalidAuditQueryException extends RuntimeException {

    public InvalidAuditQueryException(String message) {
        super(message);
    }
}
