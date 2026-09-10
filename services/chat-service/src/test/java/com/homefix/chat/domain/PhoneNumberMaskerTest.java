package com.homefix.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for phone-number masking in chat message bodies (Requirement 18.8).
 */
class PhoneNumberMaskerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "+919876543210",
            "9876543210",
            "+91 98765 43210",
            "98765-43210",
            "call me on +1 (do not) 415.555.0198 later"
    })
    void masksDigitsOfPhoneLikeSequences(String input) {
        String masked = PhoneNumberMasker.mask(input);
        // No run of 4+ consecutive digits should survive masking.
        assertThat(masked).doesNotContainPattern("\\d{4,}");
        assertThat(masked).contains("*");
    }

    @Test
    void leavesOrdinaryTextUntouched() {
        String text = "the sink is leaking, please bring a wrench";
        assertThat(PhoneNumberMasker.mask(text)).isEqualTo(text);
    }

    @Test
    void doesNotMaskShortNumbersLikeQuantities() {
        String text = "bring 2 washers and 3 bolts";
        assertThat(PhoneNumberMasker.mask(text)).isEqualTo(text);
    }

    @Test
    void nullAndEmptyArePassedThrough() {
        assertThat(PhoneNumberMasker.mask(null)).isNull();
        assertThat(PhoneNumberMasker.mask("")).isEmpty();
    }

    @Test
    void preservesLeadingPlusAndSeparators() {
        String masked = PhoneNumberMasker.mask("+91 98765 43210");
        assertThat(masked).startsWith("+");
        assertThat(masked).contains(" ");
        assertThat(masked).doesNotContainPattern("\\d");
    }
}
