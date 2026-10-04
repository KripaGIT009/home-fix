package com.homefix.auth.emailauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.auth.config.EmailAuthProperties;
import com.homefix.auth.otp.OtpCodeGenerator;
import com.homefix.auth.support.InMemoryEmailCodeStore;

/**
 * Tests for {@link EmailCodes}: single use, the attempt bound and expiry (Property EA2), the
 * per-address and per-IP throttles (Requirements 1.8, 8.1), and that purposes do not cross.
 */
class EmailCodesTest {

    private static final String EMAIL = "asha@example.com";

    private InMemoryEmailCodeStore store;
    private EmailCodes codes;

    @BeforeEach
    void setUp() {
        store = new InMemoryEmailCodeStore();
        codes = new EmailCodes(store, new OtpCodeGenerator(), new EmailAuthProperties());
    }

    @Test
    void aRightCode_worksOnce_andReturnsItsPayload() {
        String code = codes.issue(CodePurpose.EMAIL_CHANGE, "user-1", "new@example.com");

        assertThat(codes.consume(CodePurpose.EMAIL_CHANGE, "user-1", code)).contains("new@example.com");
        assertThatThrownBy(() -> codes.consume(CodePurpose.EMAIL_CHANGE, "user-1", code))
                .extracting("errorCode").isEqualTo("CODE_EXPIRED");
    }

    @Test
    void wrongCodes_countDown_andTheFifthSpendsTheCode() {
        String code = codes.issue(CodePurpose.SIGNUP, EMAIL, null);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int left = 4; left >= 0; left--) {
            assertThatThrownBy(() -> codes.consume(CodePurpose.SIGNUP, EMAIL, wrong))
                    .hasMessageContaining(left + " attempt(s) left")
                    .extracting("errorCode").isEqualTo("INVALID_CODE");
        }
        // Even the right code is refused now.
        assertThatThrownBy(() -> codes.consume(CodePurpose.SIGNUP, EMAIL, code))
                .extracting("errorCode").isEqualTo("CODE_EXPIRED");
    }

    @Test
    void aCode_expiresAfterItsLifetime() {
        String code = codes.issue(CodePurpose.RESET, EMAIL, null);
        store.advance(Duration.ofMinutes(10).plusSeconds(1));

        assertThatThrownBy(() -> codes.consume(CodePurpose.RESET, EMAIL, code))
                .extracting("errorCode").isEqualTo("CODE_EXPIRED");
    }

    @Test
    void aCode_isGoodOnlyForItsOwnPurpose() {
        String code = codes.issue(CodePurpose.SIGNUP, EMAIL, null);

        assertThatThrownBy(() -> codes.consume(CodePurpose.RESET, EMAIL, code))
                .extracting("errorCode").isEqualTo("CODE_EXPIRED");
        assertThat(codes.consume(CodePurpose.SIGNUP, EMAIL, code)).isEmpty();
    }

    @Test
    void sendsToOneAddress_areSpacedByTheCooldown_andCappedPerHour() {
        codes.requireSendAllowed(EMAIL);
        assertThatThrownBy(() -> codes.requireSendAllowed(EMAIL))
                .extracting("errorCode", "retryAfterSeconds").containsExactly("TOO_MANY_REQUESTS", 60L);

        for (int i = 2; i <= 5; i++) {
            store.advance(Duration.ofSeconds(61));
            codes.requireSendAllowed(EMAIL);
        }
        store.advance(Duration.ofSeconds(61));
        assertThatThrownBy(() -> codes.requireSendAllowed(EMAIL))
                .extracting("errorCode").isEqualTo("TOO_MANY_REQUESTS");

        // Another address is not affected.
        codes.requireSendAllowed("other@example.com");
    }

    @Test
    void onePerIp_isCappedPerHour_perAction() {
        for (int i = 0; i < 10; i++) {
            codes.requireIpAllowed("signup", "203.0.113.7");
        }
        assertThatThrownBy(() -> codes.requireIpAllowed("signup", "203.0.113.7"))
                .extracting("errorCode").isEqualTo("TOO_MANY_REQUESTS");
        codes.requireIpAllowed("reset", "203.0.113.7");
        codes.requireIpAllowed("signup", "203.0.113.8");
        // An unknown address is never counted.
        for (int i = 0; i < 20; i++) {
            codes.requireIpAllowed("signup", null);
        }
    }

    @Test
    void passwordPolicy_needsLengthALetterAndADigit_withinBcryptsLimit() {
        assertThatThrownBy(() -> PasswordPolicy.requireAcceptable("abc123")).extracting("errorCode")
                .isEqualTo("WEAK_PASSWORD");
        assertThatThrownBy(() -> PasswordPolicy.requireAcceptable("abcdefghij")).extracting("errorCode")
                .isEqualTo("WEAK_PASSWORD");
        assertThatThrownBy(() -> PasswordPolicy.requireAcceptable("1234567890")).extracting("errorCode")
                .isEqualTo("WEAK_PASSWORD");
        assertThatThrownBy(() -> PasswordPolicy.requireAcceptable("a1" + "€".repeat(24))).extracting("errorCode")
                .isEqualTo("WEAK_PASSWORD");
        PasswordPolicy.requireAcceptable("homefix2026");
    }
}
