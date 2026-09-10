package com.homefix.provider.crypto;

/**
 * Raised when encryption or decryption via {@link KmsEncryptionPort} fails.
 */
public class EncryptionException extends RuntimeException {

    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }

    public EncryptionException(String message) {
        super(message);
    }
}
