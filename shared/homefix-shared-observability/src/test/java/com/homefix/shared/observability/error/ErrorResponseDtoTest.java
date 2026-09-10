package com.homefix.shared.observability.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponseDtoTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void serialisesAllFields() throws Exception {
        ErrorResponseDto dto = ErrorResponseDto.builder()
                .errorCode("BOOKING_NOT_FOUND")
                .message("No booking with that reference")
                .correlationId("corr-123")
                .addDetail("reference did not match")
                .timestamp(Instant.parse("2026-09-07T05:56:33.454Z"))
                .build();

        String json = mapper.writeValueAsString(dto);

        assertThat(json).contains("\"errorCode\":\"BOOKING_NOT_FOUND\"");
        assertThat(json).contains("\"message\":\"No booking with that reference\"");
        assertThat(json).contains("\"correlationId\":\"corr-123\"");
        assertThat(json).contains("\"details\":[\"reference did not match\"]");
        assertThat(json).contains("\"timestamp\"");
    }

    @Test
    void omitsEmptyOptionalFields() throws Exception {
        ErrorResponseDto dto = new ErrorResponseDto("VALIDATION_ERROR", "bad input", null);

        String json = mapper.writeValueAsString(dto);

        // NON_EMPTY: empty details list and null correlationId are omitted.
        assertThat(json).doesNotContain("details");
        assertThat(json).doesNotContain("correlationId");
        assertThat(json).contains("\"errorCode\":\"VALIDATION_ERROR\"");
    }

    @Test
    void roundTripsThroughJackson() throws Exception {
        ErrorResponseDto original = ErrorResponseDto.builder()
                .errorCode("PAYMENT_FAILED")
                .message("gateway declined")
                .correlationId("corr-9")
                .details(List.of("code=51", "insufficient funds"))
                .build();

        String json = mapper.writeValueAsString(original);
        ErrorResponseDto parsed = mapper.readValue(json, ErrorResponseDto.class);

        assertThat(parsed.getErrorCode()).isEqualTo("PAYMENT_FAILED");
        assertThat(parsed.getMessage()).isEqualTo("gateway declined");
        assertThat(parsed.getCorrelationId()).isEqualTo("corr-9");
        assertThat(parsed.getDetails()).containsExactly("code=51", "insufficient funds");
    }

    @Test
    void settersAndConstructorBehaveConsistently() {
        ErrorResponseDto dto = new ErrorResponseDto();
        dto.setErrorCode("E1");
        dto.setMessage("m");
        dto.setCorrelationId("c1");
        dto.setDetails(null); // null-safe -> empty list
        dto.setTimestamp(Instant.EPOCH);

        assertThat(dto.getErrorCode()).isEqualTo("E1");
        assertThat(dto.getMessage()).isEqualTo("m");
        assertThat(dto.getCorrelationId()).isEqualTo("c1");
        assertThat(dto.getDetails()).isEmpty();
        assertThat(dto.getTimestamp()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void defaultTimestampIsPopulated() {
        assertThat(new ErrorResponseDto().getTimestamp()).isNotNull();
    }
}
