package com.homefix.invoice.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.homefix.invoice.config.InvoiceProperties;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InMemoryInvoiceStorageAdapter}: it stores objects (SSE implicit) and mints
 * a signed URL whose absolute expiry equals {@code now + ttl}, making the 72-hour window
 * observable (Requirements 13.2, 13.3). Lives in the adapter's package to use its package-private
 * fixed-clock constructor.
 */
class InMemoryInvoiceStorageAdapterTest {

    private static final Instant NOW = Instant.parse("2024-07-15T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private InvoiceProperties props() {
        InvoiceProperties p = new InvoiceProperties();
        p.getS3().setBucket("homefix-invoices");
        p.getS3().setRegion("ap-south-1");
        return p;
    }

    @Test
    void storesObjectAndMintsSignedUrlWithAbsoluteExpiry() {
        InMemoryInvoiceStorageAdapter storage = new InMemoryInvoiceStorageAdapter(props(), FIXED_CLOCK);
        String key = "invoices/cust/INV-2024-07-000001.pdf";

        String written = storage.store(key, "PDF-BYTES".getBytes());
        assertThat(written).isEqualTo(key);
        assertThat(storage.contains(key)).isTrue();
        assertThat(storage.contains("missing")).isFalse();

        SignedUrl signed = storage.signedUrl(key, Duration.ofHours(72));
        assertThat(signed.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(72)));
        assertThat(signed.url())
                .contains("homefix-invoices.s3.ap-south-1.amazonaws.com/" + key)
                .contains("X-Amz-Expires=" + Duration.ofHours(72).toSeconds())
                .contains("X-Amz-Signature=");
    }

    @Test
    void defaultConstructorUsesSystemClock() {
        InMemoryInvoiceStorageAdapter storage = new InMemoryInvoiceStorageAdapter(props());
        storage.store("k", new byte[]{1, 2, 3});
        assertThat(storage.contains("k")).isTrue();
        SignedUrl signed = storage.signedUrl("k", Duration.ofHours(72));
        assertThat(signed.expiresAt()).isAfter(Instant.now());
    }
}
