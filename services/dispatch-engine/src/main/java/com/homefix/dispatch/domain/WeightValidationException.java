package com.homefix.dispatch.domain;

/**
 * Thrown when an Admin-submitted dispatch weight set is invalid: some weight falls outside
 * [0.0, 1.0], or the weights do not sum to exactly 1.0 (Requirement 19.5, Property 19).
 *
 * <p>The message identifies which condition failed so the caller can surface it verbatim; the
 * existing weights are left unchanged by the caller when this is thrown.
 */
public class WeightValidationException extends RuntimeException {

    public WeightValidationException(String message) {
        super(message);
    }
}
