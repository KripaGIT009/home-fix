package com.homefix.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.domain.TransactionStatus;

/**
 * The signed body of a gateway callback (Requirement 12.5), parsed only <em>after</em> its signature
 * has been verified. It is the single source of truth for what a callback does: the target
 * transaction, the outcome, the amount and the failure reason all come from here, so none of them
 * can be altered without invalidating the signature.
 *
 * <p>Wire contract (a JSON object, UTF-8, signed byte-for-byte as sent):
 *
 * <pre>
 * {
 *   "eventId":       "evt_9a1c...",                            // required, gateway event id, max 128
 *   "transactionId": "7e4b1c93-0d58-4a26-bf71-3c9e5d2a8b64",   // required, must equal the path id
 *   "gatewayId":     "razorpay",                               // required, must equal the gateway
 *   "status":        "SUCCEEDED" | "FAILED",                   // required, exact upper case
 *   "amount":        "1250.00",                                // required, major units, number or string
 *   "failureReason": "card declined",                          // optional, used only when FAILED
 *   "timestamp":     "2026-10-02T10:15:30Z"                    // required, ISO-8601 instant
 * }
 * </pre>
 *
 * <p>Unknown fields are ignored so a gateway may sign extra data. Any missing or malformed required
 * field rejects the callback with {@code INVALID_CALLBACK_PAYLOAD}.
 */
public record SignedCallbackPayload(
        String eventId,
        UUID transactionId,
        String gatewayId,
        Outcome outcome,
        BigDecimal amount,
        String failureReason,
        Instant timestamp) {

    /** The charge outcome the gateway reports. */
    public enum Outcome {
        SUCCEEDED,
        FAILED;

        /**
         * @return whether a transaction already in {@code status} is consistent with this outcome,
         *         i.e. re-applying the callback would be a harmless duplicate. A refunded payment did
         *         succeed, so SUCCEEDED agrees with the refund states too.
         */
        public boolean agreesWith(TransactionStatus status) {
            return switch (this) {
                case SUCCEEDED -> status == TransactionStatus.SUCCESS
                        || status == TransactionStatus.PARTIALLY_REFUNDED
                        || status == TransactionStatus.REFUNDED;
                case FAILED -> status == TransactionStatus.FAILED;
            };
        }
    }

    private static final int MAX_EVENT_ID_LENGTH = 128;
    private static final int MAX_GATEWAY_ID_LENGTH = 32;

    /** Exact decimals: a float amount must not be rounded through {@code double} before comparison. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /**
     * Parses and validates a signed payload. Call only after the signature has been verified.
     *
     * @throws PaymentException 400 {@code INVALID_CALLBACK_PAYLOAD} if the payload is not the
     *                          documented JSON object or a required field is missing or malformed.
     */
    public static SignedCallbackPayload parse(String payload) {
        JsonNode root;
        try {
            root = payload == null ? null : MAPPER.readTree(payload);
        } catch (JsonProcessingException e) {
            throw PaymentException.invalidCallbackPayload("Callback payload is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw PaymentException.invalidCallbackPayload("Callback payload must be a JSON object");
        }

        String eventId = requiredText(root, "eventId", MAX_EVENT_ID_LENGTH);
        UUID transactionId = parseUuid(requiredText(root, "transactionId", 64));
        String gatewayId = requiredText(root, "gatewayId", MAX_GATEWAY_ID_LENGTH);
        Outcome outcome = parseOutcome(requiredText(root, "status", 16));
        BigDecimal amount = requiredAmount(root);
        Instant timestamp = parseInstant(requiredText(root, "timestamp", 64));

        String failureReason = null;
        JsonNode reason = root.get("failureReason");
        if (outcome == Outcome.FAILED && reason != null && reason.isTextual()) {
            String text = reason.asText();
            failureReason = text.length() > PaymentTransaction.FAILURE_REASON_MAX_LENGTH
                    ? text.substring(0, PaymentTransaction.FAILURE_REASON_MAX_LENGTH)
                    : text;
        }
        return new SignedCallbackPayload(eventId, transactionId, gatewayId, outcome, amount,
                failureReason, timestamp);
    }

    private static String requiredText(JsonNode root, String field, int maxLength) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw PaymentException.invalidCallbackPayload(
                    "Callback payload field '" + field + "' is required and must be a non-blank string");
        }
        String value = node.asText();
        if (value.length() > maxLength) {
            throw PaymentException.invalidCallbackPayload(
                    "Callback payload field '" + field + "' exceeds " + maxLength + " characters");
        }
        return value;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw PaymentException.invalidCallbackPayload(
                    "Callback payload field 'transactionId' is not a UUID");
        }
    }

    private static Outcome parseOutcome(String value) {
        try {
            return Outcome.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw PaymentException.invalidCallbackPayload(
                    "Callback payload field 'status' must be SUCCEEDED or FAILED");
        }
    }

    private static BigDecimal requiredAmount(JsonNode root) {
        JsonNode node = root.get("amount");
        BigDecimal amount = null;
        if (node != null && node.isNumber()) {
            amount = node.decimalValue();
        } else if (node != null && node.isTextual()) {
            try {
                amount = new BigDecimal(node.asText().trim());
            } catch (NumberFormatException e) {
                amount = null;
            }
        }
        if (amount == null || amount.signum() <= 0) {
            throw PaymentException.invalidCallbackPayload(
                    "Callback payload field 'amount' is required and must be a positive decimal");
        }
        return amount;
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw PaymentException.invalidCallbackPayload(
                    "Callback payload field 'timestamp' must be an ISO-8601 instant, e.g. 2026-10-02T10:15:30Z");
        }
    }
}
