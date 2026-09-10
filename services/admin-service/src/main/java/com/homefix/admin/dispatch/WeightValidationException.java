package com.homefix.admin.dispatch;

/**
 * Raised when submitted dispatch matching weights violate the range or sum constraint
 * (Requirement 19.5, Property 19). The message names the failing condition so the Admin sees
 * exactly which rule was broken; the existing weights are left unchanged.
 */
public class WeightValidationException extends RuntimeException {

    public WeightValidationException(String message) {
        super(message);
    }
}
