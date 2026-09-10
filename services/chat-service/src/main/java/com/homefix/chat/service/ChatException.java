package com.homefix.chat.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Domain exception for chat failures, carrying the HTTP status, a stable error code, and optional
 * detail messages that the REST layer surfaces via the shared {@code ErrorResponseDto} (Task 5).
 */
public class ChatException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<String> details = new ArrayList<>();

    public ChatException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public ChatException(HttpStatus status, String errorCode, String message, List<String> details) {
        this(status, errorCode, message);
        if (details != null) {
            this.details.addAll(details);
        }
    }

    /** The channel does not exist for the requested booking. */
    public static ChatException channelNotFound(String message) {
        return new ChatException(HttpStatus.NOT_FOUND, "CHANNEL_NOT_FOUND", message);
    }

    /**
     * The requesting user is neither the customer nor the provider on the booking
     * (Requirement 18.7, Property 23).
     */
    public static ChatException forbidden(String message) {
        return new ChatException(HttpStatus.FORBIDDEN, "CHANNEL_ACCESS_FORBIDDEN", message);
    }

    /** A send was attempted on a deactivated channel (Requirement 18.6). */
    public static ChatException channelDeactivated(String message) {
        return new ChatException(HttpStatus.CONFLICT, "CHANNEL_DEACTIVATED", message);
    }

    public static ChatException validation(String message) {
        return new ChatException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public List<String> getDetails() {
        return details;
    }
}
