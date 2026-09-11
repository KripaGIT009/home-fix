package com.homefix.customer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from the {@code homefix.kms.*} configuration namespace.
 */
@ConfigurationProperties(prefix = "homefix.kms")
public class CustomerCryptoProperties {

    /** Active KMS adapter selector (e.g. {@code local}, {@code aws}). */
    private String provider = "local";

    /**
     * Base64-encoded 256-bit data key used by the local AES adapter (dev/test only).
     *
     * <p>Deliberately has no default: an all-zero fallback key would mean every deployment that
     * forgot to supply {@code KMS_LOCAL_DATA_KEY} encrypted customer PII with a publicly known
     * key. {@code LocalAesKmsAdapter} refuses to start when it is missing.
     */
    private String localDataKey;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getLocalDataKey() {
        return localDataKey;
    }

    public void setLocalDataKey(String localDataKey) {
        this.localDataKey = localDataKey;
    }
}
