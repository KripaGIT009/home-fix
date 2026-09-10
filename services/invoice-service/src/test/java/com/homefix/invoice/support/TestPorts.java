package com.homefix.invoice.support;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.homefix.invoice.alert.OpsAlertPort;
import com.homefix.invoice.details.InvoiceDetails;
import com.homefix.invoice.details.InvoiceDetailsPort;
import com.homefix.invoice.domain.InvoiceData;
import com.homefix.invoice.domain.PriceLineItem;
import com.homefix.invoice.notification.InvoiceNotificationPort;
import com.homefix.invoice.pdf.InvoicePdfException;
import com.homefix.invoice.pdf.PdfGeneratorPort;
import com.homefix.invoice.storage.InvoiceStoragePort;
import com.homefix.invoice.storage.SignedUrl;

/** Small hand-written test doubles for the Invoice Service ports (no Spring, no Mockito needed). */
public final class TestPorts {

    private TestPorts() {
    }

    /** {@link PdfGeneratorPort} that fails the first {@code failuresBeforeSuccess} calls. */
    public static final class FlakyPdfGenerator implements PdfGeneratorPort {
        private final int failuresBeforeSuccess;
        private final AtomicInteger calls = new AtomicInteger();

        public FlakyPdfGenerator(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public byte[] render(InvoiceData data) {
            int attempt = calls.incrementAndGet();
            if (attempt <= failuresBeforeSuccess) {
                throw new InvoicePdfException("simulated PDF failure on attempt " + attempt);
            }
            return ("PDF:" + data.invoiceNumber()).getBytes();
        }

        public int callCount() {
            return calls.get();
        }
    }

    /** {@link PdfGeneratorPort} that always fails. */
    public static final class AlwaysFailingPdfGenerator implements PdfGeneratorPort {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public byte[] render(InvoiceData data) {
            calls.incrementAndGet();
            throw new InvoicePdfException("permanent PDF failure");
        }

        public int callCount() {
            return calls.get();
        }
    }

    /** Recording {@link InvoiceNotificationPort}. */
    public static final class RecordingNotificationPort implements InvoiceNotificationPort {
        public volatile int deliveries;
        public volatile String lastUrl;
        public volatile Instant lastExpiresAt;
        public volatile String lastInvoiceNumber;

        @Override
        public void deliverInvoice(UUID customerId, UUID bookingId, String invoiceNumber, String signedUrl,
                                   Instant expiresAt) {
            deliveries++;
            lastUrl = signedUrl;
            lastExpiresAt = expiresAt;
            lastInvoiceNumber = invoiceNumber;
        }
    }

    /** Recording {@link OpsAlertPort}. */
    public static final class RecordingOpsAlertPort implements OpsAlertPort {
        public volatile int alerts;
        public volatile UUID lastBookingId;
        public volatile UUID lastPaymentId;

        @Override
        public void alertInvoiceGenerationFailed(UUID bookingId, UUID paymentId, String reason) {
            alerts++;
            lastBookingId = bookingId;
            lastPaymentId = paymentId;
        }
    }

    /** Fixed {@link InvoiceDetailsPort}. */
    public static final class FixedDetailsPort implements InvoiceDetailsPort {
        @Override
        public InvoiceDetails resolve(UUID bookingId, UUID customerId, UUID providerId) {
            return new InvoiceDetails(
                    "Jane Customer",
                    "42 Test Street",
                    "John Provider",
                    true,
                    "Leaky faucet repair",
                    List.of(new PriceLineItem("Base price", new BigDecimal("500.00"))),
                    new BigDecimal("90.00"),
                    BigDecimal.ZERO);
        }
    }

    /**
     * {@link InvoiceStoragePort} that records stores and mints a signed URL whose expiry is
     * {@code clock.now + ttl}, so the 72-hour window is assertable.
     */
    public static final class RecordingStoragePort implements InvoiceStoragePort {
        private final Clock clock;
        public volatile String lastKey;
        public volatile int stores;
        public volatile Duration lastTtl;

        public RecordingStoragePort(Clock clock) {
            this.clock = clock;
        }

        @Override
        public String store(String objectKey, byte[] pdfBytes) {
            stores++;
            lastKey = objectKey;
            return objectKey;
        }

        @Override
        public SignedUrl signedUrl(String objectKey, Duration ttl) {
            lastTtl = ttl;
            return new SignedUrl("https://example/" + objectKey, clock.instant().plus(ttl));
        }
    }
}
