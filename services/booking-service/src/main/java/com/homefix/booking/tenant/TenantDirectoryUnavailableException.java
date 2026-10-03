package com.homefix.booking.tenant;

/**
 * The Provider Service's Tenant registry could not be asked: down, timing out, or refusing the
 * internal credential. Distinct from "no such Tenant", which {@link TenantDirectoryPort} answers
 * with an empty result.
 */
public class TenantDirectoryUnavailableException extends RuntimeException {

    public TenantDirectoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
