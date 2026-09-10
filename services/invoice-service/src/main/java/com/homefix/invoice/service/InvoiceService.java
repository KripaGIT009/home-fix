package com.homefix.invoice.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.homefix.invoice.alert.OpsAlertPort;
import com.homefix.invoice.config.InvoiceProperties;
import com.homefix.invoice.details.InvoiceDetails;
import com.homefix.invoice.details.InvoiceDetailsPort;
import com.homefix.invoice.domain.Invoice;
import com.homefix.invoice.domain.InvoiceData;
import com.homefix.invoice.domain.InvoiceNumberGenerator;
import com.homefix.invoice.domain.InvoiceRepository;
import com.homefix.invoice.event.PaymentCompletedEvent;
import com.homefix.invoice.pdf.InvoicePdfException;
import com.homefix.invoice.pdf.PdfGeneratorPort;
import com.homefix.invoice.storage.InvoiceStoragePort;
import com.homefix.invoice.storage.SignedUrl;
import com.homefix.invoice.notification.InvoiceNotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Orchestrates invoice generation from a {@code PaymentCompleted} event (Requirement 13):
 *
 * <ol>
 *   <li>Skips generation if an invoice already exists for the payment (idempotent, in addition to
 *       the consumer-level dedup).</li>
 *   <li>Assigns a globally-unique per-month invoice number (Requirement 13.4, Property 14).</li>
 *   <li>Renders the PDF, retrying up to the configured limit on failure; on exhaustion it alerts
 *       operations and returns without throwing, so a rendering fault never blocks or dead-letters
 *       the payment flow (Requirement 13.7).</li>
 *   <li>Stores the PDF in S3 with server-side encryption (Requirement 13.2).</li>
 *   <li>Persists the invoice record (anchors 24-month retention, Requirement 13.5).</li>
 *   <li>Delivers a 72-hour signed URL to the customer via the Notification Service
 *       (Requirement 13.3).</li>
 * </ol>
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final InvoiceNumberGenerator invoiceNumberGenerator;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceDetailsPort invoiceDetailsPort;
    private final PdfGeneratorPort pdfGeneratorPort;
    private final InvoiceStoragePort storagePort;
    private final InvoiceNotificationPort notificationPort;
    private final OpsAlertPort opsAlertPort;
    private final InvoiceProperties properties;
    private final Clock clock;
    private final Sleeper sleeper;

    // These classes keep a second, package-private constructor so tests can pin the Clock.
    // With more than one constructor Spring will not guess: without @Autowired it falls back
    // to a no-arg constructor that does not exist and the bean fails to instantiate.
    @Autowired
    public InvoiceService(InvoiceNumberGenerator invoiceNumberGenerator,
                          InvoiceRepository invoiceRepository,
                          InvoiceDetailsPort invoiceDetailsPort,
                          PdfGeneratorPort pdfGeneratorPort,
                          InvoiceStoragePort storagePort,
                          InvoiceNotificationPort notificationPort,
                          OpsAlertPort opsAlertPort,
                          InvoiceProperties properties) {
        this(invoiceNumberGenerator, invoiceRepository, invoiceDetailsPort, pdfGeneratorPort,
                storagePort, notificationPort, opsAlertPort, properties, Clock.systemUTC(), Thread::sleep);
    }

    // Visible for testing so unit tests avoid real backoff sleeps and can pin the clock.
    InvoiceService(InvoiceNumberGenerator invoiceNumberGenerator,
                   InvoiceRepository invoiceRepository,
                   InvoiceDetailsPort invoiceDetailsPort,
                   PdfGeneratorPort pdfGeneratorPort,
                   InvoiceStoragePort storagePort,
                   InvoiceNotificationPort notificationPort,
                   OpsAlertPort opsAlertPort,
                   InvoiceProperties properties,
                   Clock clock,
                   Sleeper sleeper) {
        this.invoiceNumberGenerator = invoiceNumberGenerator;
        this.invoiceRepository = invoiceRepository;
        this.invoiceDetailsPort = invoiceDetailsPort;
        this.pdfGeneratorPort = pdfGeneratorPort;
        this.storagePort = storagePort;
        this.notificationPort = notificationPort;
        this.opsAlertPort = opsAlertPort;
        this.properties = properties;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * Generates and delivers the invoice for a completed payment.
     *
     * @return the persisted {@link Invoice}, or {@code null} if generation was skipped as a
     *         duplicate or abandoned after PDF retries were exhausted.
     */
    public Invoice generateForPayment(PaymentCompletedEvent event) {
        if (invoiceRepository.existsByPaymentId(event.paymentId())) {
            log.debug("Invoice already exists for payment {}; skipping duplicate generation",
                    event.paymentId());
            return invoiceRepository.findByPaymentId(event.paymentId()).orElse(null);
        }

        Instant generatedAt = clock.instant();
        InvoiceDetails details = invoiceDetailsPort.resolve(
                event.bookingId(), event.customerId(), event.providerId());
        String invoiceNumber = invoiceNumberGenerator.nextInvoiceNumber(generatedAt);
        InvoiceData data = toInvoiceData(event, details, invoiceNumber, generatedAt);

        byte[] pdf = renderWithRetry(data, event);
        if (pdf == null) {
            // Retries exhausted; ops already alerted. Do not block the payment flow (Requirement 13.7).
            return null;
        }

        String objectKey = objectKey(event, invoiceNumber);
        storagePort.store(objectKey, pdf);

        Invoice invoice = Invoice.create(invoiceNumber, event.bookingId(), event.paymentId(),
                event.customerId(), event.providerId(), objectKey,
                event.amount(), event.platformFee(), event.providerNetEarning(), generatedAt);
        invoiceRepository.save(invoice);

        deliverSignedUrl(event, invoice);
        return invoice;
    }

    private byte[] renderWithRetry(InvoiceData data, PaymentCompletedEvent event) {
        int maxAttempts = Math.max(1, properties.getMaxPdfRetries());
        InvoicePdfException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return pdfGeneratorPort.render(data);
            } catch (InvoicePdfException ex) {
                lastFailure = ex;
                log.warn("Attempt {}/{} to render invoice {} failed: {}",
                        attempt, maxAttempts, data.invoiceNumber(), ex.toString());
                if (attempt < maxAttempts) {
                    backoff(attempt);
                }
            }
        }
        log.error("Invoice PDF generation exhausted after {} attempts; booking={} payment={}",
                maxAttempts, event.bookingId(), event.paymentId());
        opsAlertPort.alertInvoiceGenerationFailed(event.bookingId(), event.paymentId(),
                lastFailure == null ? "unknown" : lastFailure.getMessage());
        return null;
    }

    private void deliverSignedUrl(PaymentCompletedEvent event, Invoice invoice) {
        SignedUrl signedUrl = storagePort.signedUrl(invoice.getS3Key(), properties.getSignedUrlTtl());
        try {
            notificationPort.deliverInvoice(event.customerId(), event.bookingId(),
                    invoice.getInvoiceNumber(), signedUrl.url(), signedUrl.expiresAt());
        } catch (RuntimeException e) {
            // Delivery is best-effort; the invoice is already persisted and accessible via history.
            log.warn("Failed to deliver signed URL for invoice {}; it remains available in history",
                    invoice.getInvoiceNumber());
        }
    }

    private InvoiceData toInvoiceData(PaymentCompletedEvent event, InvoiceDetails details,
                                      String invoiceNumber, Instant generatedAt) {
        return new InvoiceData(
                invoiceNumber,
                generatedAt,
                event.bookingId(),
                event.paymentId(),
                event.customerId(),
                event.providerId(),
                details.customerName(),
                details.customerAddress(),
                details.providerName(),
                details.providerVerified(),
                details.serviceDescription(),
                details.lineItems(),
                details.taxAmount(),
                properties.getTaxIdentifier(),
                details.discountAmount(),
                event.amount(),
                event.paymentMethod());
    }

    private void backoff(int attempt) {
        long millis = properties.getPdfRetryBackoff().toMillis() * (1L << (attempt - 1));
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting PDF retry backoff", e);
        }
    }

    private static String objectKey(PaymentCompletedEvent event, String invoiceNumber) {
        return "invoices/" + event.customerId() + "/" + invoiceNumber + ".pdf";
    }

    /** Seam so tests can avoid real sleeps. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
