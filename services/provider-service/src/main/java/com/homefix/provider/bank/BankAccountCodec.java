package com.homefix.provider.bank;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.homefix.provider.crypto.EncryptionException;
import com.homefix.provider.crypto.KmsEncryptionPort;

/**
 * Seals a provider's bank account into the single encrypted value stored in
 * {@code provider_profile.bank_account_encrypted} (Requirement 4.9), and reads it back as a
 * {@link BankAccountView} that never shows the number or the ciphertext.
 *
 * <h2>Stored format</h2>
 * <p>The clear text is a small JSON document, {@code {"v":1,"holder":…,"number":…,"ifsc":…}},
 * encrypted as one value through {@link KmsEncryptionPort}. Holder, number and IFSC therefore
 * travel and rotate together, and the masked label is computed from the decrypted number.
 *
 * <h2>Legacy values</h2>
 * <p>Accounts stored before this format are a bare reference encrypted directly (for example
 * {@code HDFC0001234/50100123456789}), with no holder. They are read gracefully: the IFSC prefix is
 * shown when the reference contains one, and the last four digits that remain. A value that cannot
 * be decrypted at all is shown as a bare {@code ••••} and logged, never as ciphertext.
 */
@Component
public class BankAccountCodec {

    private static final Logger log = LoggerFactory.getLogger(BankAccountCodec.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int FORMAT_VERSION = 1;

    /** Four bullets (U+2022) standing in for the hidden digits. */
    static final String HIDDEN = "••••";

    private static final Pattern IFSC_ANYWHERE = Pattern.compile("[A-Z]{4}0[A-Z0-9]{6}");

    private final KmsEncryptionPort kms;

    public BankAccountCodec(KmsEncryptionPort kms) {
        this.kms = kms;
    }

    /** Encrypts {@code details} as one value, safe to persist. */
    public String seal(BankAccountDetails details) {
        ObjectNode node = JSON.createObjectNode()
                .put("v", FORMAT_VERSION)
                .put("holder", details.holderName())
                .put("number", details.accountNumber())
                .put("ifsc", details.ifsc());
        try {
            return kms.encrypt(JSON.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            throw new EncryptionException("Failed to serialise bank account", e);
        }
    }

    /**
     * The displayable form of a stored account, or {@code null} when nothing is stored.
     *
     * @param encrypted the stored value ({@link #seal} output, or a legacy encrypted reference)
     * @param verified  the stored verification flag, passed through
     */
    public BankAccountView describe(String encrypted, boolean verified) {
        if (encrypted == null) {
            return null;
        }
        String plain;
        try {
            plain = kms.decrypt(encrypted);
        } catch (EncryptionException e) {
            log.warn("Stored bank account could not be decrypted; showing it fully masked ({})",
                    e.getMessage());
            return new BankAccountView(HIDDEN, verified, null);
        }
        BankAccountDetails details = parse(plain);
        if (details != null) {
            return new BankAccountView(mask(details.ifsc(), details.accountNumber()), verified,
                    details.holderName());
        }
        return new BankAccountView(maskLegacy(plain), verified, null);
    }

    /**
     * {@code "<first 4 of IFSC> ••••<last 4 digits>"}; either part is left out when unknown, so
     * the result is at worst a bare {@code ••••}.
     */
    public static String mask(String ifsc, String accountNumber) {
        String digits = accountNumber == null ? "" : accountNumber.replaceAll("\\D", "");
        String tail = digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
        String prefix = ifsc == null || ifsc.length() < 4 ? null : ifsc.substring(0, 4);
        return (prefix == null ? "" : prefix + " ") + HIDDEN + tail;
    }

    /** The JSON format, or {@code null} when {@code plain} is not one (a legacy reference). */
    private static BankAccountDetails parse(String plain) {
        if (plain == null || !plain.stripLeading().startsWith("{")) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(plain);
            if (node == null || !node.isObject() || !node.hasNonNull("number")) {
                return null;
            }
            return new BankAccountDetails(text(node, "holder"), text(node, "number"), text(node, "ifsc"));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** Masks a legacy bare reference: its IFSC prefix when it has one, and its last four digits. */
    private static String maskLegacy(String plain) {
        if (plain == null) {
            return HIDDEN;
        }
        String upper = plain.toUpperCase(Locale.ROOT);
        Matcher ifsc = IFSC_ANYWHERE.matcher(upper);
        if (ifsc.find()) {
            String rest = upper.substring(0, ifsc.start()) + upper.substring(ifsc.end());
            return mask(ifsc.group(), rest);
        }
        return mask(null, upper);
    }
}
