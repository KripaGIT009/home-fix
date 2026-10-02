package com.homefix.dispatch.domain;

/**
 * Thrown when an Admin-submitted search-radius or offer-timeout setting is outside its permitted
 * range (Requirements 8.2, 8.5, 8.8). Like {@link WeightValidationException}, the message names
 * the failing field so the caller can surface it verbatim, and the active settings are left
 * unchanged.
 */
public class DispatchSettingsValidationException extends RuntimeException {

    public DispatchSettingsValidationException(String message) {
        super(message);
    }
}
