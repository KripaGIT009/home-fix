package com.homefix.payment.crypto;

/**
 * Raised when encryption or decryption of a stored payment credential fails (Requirement 12.9).
 */
public class EncryptionException extends RuntimeException {

    public EncryptionException(String message) {
        super(message);
    }

    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
