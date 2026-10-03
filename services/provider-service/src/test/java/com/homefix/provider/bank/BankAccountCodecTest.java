package com.homefix.provider.bank;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.homefix.provider.crypto.KmsEncryptionPort;
import com.homefix.provider.crypto.LocalAesKmsAdapter;

/**
 * Unit tests for {@link BankAccountCodec} and the two {@link BankAccountVerificationPort} adapters:
 * the sealed value round-trips, the mask is computed from the decrypted number (never from the
 * ciphertext), legacy bare references and undecryptable values are read gracefully, and the
 * simulator / manual outcomes.
 */
class BankAccountCodecTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String OTHER_KEY = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU=";
    private static final String HIDDEN = "••••";

    private final KmsEncryptionPort kms = new LocalAesKmsAdapter(KEY);
    private final BankAccountCodec codec = new BankAccountCodec(kms);
    private final BankAccountDetails details =
            new BankAccountDetails("Ravi Kumar", "50100123456789", "HDFC0001234");

    @Test
    void sealedAccountIsDescribedMaskedWithTheHolder() {
        String sealed = codec.seal(details);

        BankAccountView view = codec.describe(sealed, true);

        assertThat(sealed).startsWith("v1:").doesNotContain("50100123456789");
        assertThat(view).isEqualTo(new BankAccountView("HDFC " + HIDDEN + "6789", true, "Ravi Kumar"));
    }

    @Test
    void identicalAccountsSealDifferentlyButDescribeAlike() {
        String a = codec.seal(details);
        String b = codec.seal(details);

        assertThat(a).isNotEqualTo(b);
        assertThat(codec.describe(a, false).masked()).isEqualTo(codec.describe(b, false).masked());
    }

    @Test
    void nothingStoredIsNull() {
        assertThat(codec.describe(null, false)).isNull();
    }

    @Test
    void legacyIfscSlashNumberReferenceShowsItsPrefixAndLastDigits() {
        String legacy = kms.encrypt("HDFC0001234/50100123456789");

        assertThat(codec.describe(legacy, true))
                .isEqualTo(new BankAccountView("HDFC " + HIDDEN + "6789", true, null));
    }

    @Test
    void legacyBareReferenceShowsItsLastDigits() {
        assertThat(codec.describe(kms.encrypt("ACCT-12345"), false).masked()).isEqualTo(HIDDEN + "2345");
        assertThat(codec.describe(kms.encrypt("no digits here"), false).masked()).isEqualTo(HIDDEN);
    }

    @Test
    void anUndecryptableValueIsFullyMaskedNeverShownAsCiphertext() {
        String foreign = new LocalAesKmsAdapter(OTHER_KEY).encrypt("HDFC0001234/50100123456789");

        BankAccountView fromOtherKey = codec.describe(foreign, true);
        BankAccountView garbage = codec.describe("not-a-token-1234", false);

        assertThat(fromOtherKey.masked()).isEqualTo(HIDDEN);
        assertThat(fromOtherKey.verified()).isTrue();
        assertThat(garbage.masked()).isEqualTo(HIDDEN);
    }

    @Test
    void maskNeverContainsTheCiphertextTail() {
        String sealed = codec.seal(details);
        String ciphertextTail = sealed.substring(sealed.length() - 4);

        String masked = codec.describe(sealed, false).masked();

        assertThat(masked).endsWith("6789");
        if (!ciphertextTail.equals("6789")) {
            assertThat(masked).doesNotContain(ciphertextTail);
        }
    }

    @Test
    void toStringDoesNotLeakTheNumber() {
        assertThat(details.toString()).doesNotContain("50100123456789").contains("6789");
    }

    // ------------------------------------------------------------------ verification adapters

    @Test
    void simulatorVerifiesOnlyWellFormedDetails() {
        BankAccountVerificationPort simulator = new SimulatorBankAccountVerificationAdapter();

        assertThat(simulator.verify(details)).isEqualTo(BankAccountVerificationPort.Outcome.VERIFIED);
        assertThat(simulator.verify(new BankAccountDetails("Ravi", "123", "HDFC0001234")))
                .isEqualTo(BankAccountVerificationPort.Outcome.PENDING);
    }

    @Test
    void manualNeverVerifies() {
        assertThat(new ManualBankAccountVerificationAdapter().verify(details))
                .isEqualTo(BankAccountVerificationPort.Outcome.PENDING);
    }
}
