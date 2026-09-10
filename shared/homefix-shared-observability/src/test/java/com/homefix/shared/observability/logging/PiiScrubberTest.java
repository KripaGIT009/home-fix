package com.homefix.shared.observability.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiScrubberTest {

    @Test
    void masksEmailAddresses() {
        String out = PiiScrubber.scrub("Customer contacted at john.doe@example.com today");
        assertThat(out).doesNotContain("john.doe@example.com");
        assertThat(out).contains(PiiScrubber.MASK);
    }

    @Test
    void masksPaymentCardNumbers() {
        String out = PiiScrubber.scrub("charged card 4111 1111 1111 1111 successfully");
        assertThat(out).doesNotContain("4111");
        assertThat(out).contains(PiiScrubber.MASK);
    }

    @Test
    void masksContiguousCardNumber() {
        String out = PiiScrubber.scrub("card=4111111111111111");
        assertThat(out).doesNotContain("4111111111111111");
    }

    @Test
    void masksAadhaarStyleNationalId() {
        String out = PiiScrubber.scrub("aadhaar 1234 5678 9012 on file");
        assertThat(out).doesNotContain("1234 5678 9012");
        assertThat(out).contains(PiiScrubber.MASK);
    }

    @Test
    void masksSsnStyleId() {
        String out = PiiScrubber.scrub("ssn 123-45-6789 verified");
        assertThat(out).doesNotContain("123-45-6789");
    }

    @Test
    void masksPhoneNumbers() {
        String out = PiiScrubber.scrub("call +91 98765 43210 for details");
        assertThat(out).doesNotContain("98765 43210");
        assertThat(out).contains(PiiScrubber.MASK);
    }

    @Test
    void masksSensitiveKeyValues_preservingKey() {
        String out = PiiScrubber.scrub("name=John Smith address=221B Baker Street");
        assertThat(out).contains("name=" + PiiScrubber.MASK);
        assertThat(out).contains("address=" + PiiScrubber.MASK);
        assertThat(out).doesNotContain("John Smith");
        assertThat(out).doesNotContain("Baker Street");
    }

    @Test
    void masksPasswordValue() {
        String out = PiiScrubber.scrub("login password: hunter2secret ok");
        assertThat(out).doesNotContain("hunter2secret");
    }

    @Test
    void leavesNonPiiTextUntouched() {
        String input = "Booking BKG-2026-000123 transitioned to PROVIDER_ACCEPTED";
        assertThat(PiiScrubber.scrub(input)).isEqualTo(input);
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(PiiScrubber.scrub(null)).isNull();
        assertThat(PiiScrubber.scrub("")).isEmpty();
    }
}
