package com.homefix.shared.observability.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Standard error envelope returned by every HomeFix microservice so clients see a
 * consistent shape regardless of which service failed (Task 5).
 *
 * <p>JSON shape:
 * <pre>
 * {
 *   "errorCode": "BOOKING_NOT_FOUND",
 *   "message": "No booking exists with the supplied reference",
 *   "details": ["field 'reference' did not match any record"],
 *   "correlationId": "0e9c...-uuid",
 *   "timestamp": "2026-09-07T05:56:33.454Z"
 * }
 * </pre>
 *
 * <p>{@code null}/empty optional fields are omitted from the serialised output via
 * {@link JsonInclude.Include#NON_EMPTY}.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ErrorResponseDto {

    @JsonProperty("errorCode")
    private String errorCode;

    @JsonProperty("message")
    private String message;

    @JsonProperty("details")
    private List<String> details = new ArrayList<>();

    @JsonProperty("correlationId")
    private String correlationId;

    @JsonProperty("timestamp")
    private Instant timestamp = Instant.now();

    public ErrorResponseDto() {
    }

    public ErrorResponseDto(String errorCode, String message, String correlationId) {
        this.errorCode = errorCode;
        this.message = message;
        this.correlationId = correlationId;
    }

    /**
     * Fluent builder entry point.
     */
    public static Builder builder() {
        return new Builder();
    }

    // ----- getters / setters -----

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<String> getDetails() {
        return details;
    }

    public void setDetails(List<String> details) {
        this.details = details == null ? new ArrayList<>() : details;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * Minimal builder for ergonomic construction in exception handlers.
     */
    public static final class Builder {
        private final ErrorResponseDto dto = new ErrorResponseDto();

        public Builder errorCode(String errorCode) {
            dto.errorCode = errorCode;
            return this;
        }

        public Builder message(String message) {
            dto.message = message;
            return this;
        }

        public Builder correlationId(String correlationId) {
            dto.correlationId = correlationId;
            return this;
        }

        public Builder addDetail(String detail) {
            if (detail != null) {
                dto.details.add(detail);
            }
            return this;
        }

        public Builder details(List<String> details) {
            dto.setDetails(details);
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            dto.timestamp = timestamp;
            return this;
        }

        public ErrorResponseDto build() {
            return dto;
        }
    }
}
