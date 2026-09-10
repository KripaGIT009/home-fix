package com.homefix.invoice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.homefix.invoice.config.InvoiceProperties;
import com.homefix.invoice.details.InvoiceDetailsPort;
import com.homefix.invoice.domain.Invoice;
import com.homefix.invoice.domain.InvoiceNumberGenerator;
import com.homefix.invoice.event.PaymentCompletedEvent;
import com.homefix.invoice.support.InMemoryInvoiceRepository;
import com.homefix.invoice.support.InMemoryInvoiceSequenceRepository;
import com.homefix.invoice.support.TestPorts;
import com.homefix.invoice.support.TestPorts.AlwaysFailingPdfGenerator;
import com.homefix.invoice.support.TestPorts.FlakyPdfGenerator;
import com.homefix.invoice.support.TestPorts.RecordingNotificationPort;
import com.homefix.invoice.support.TestPorts.RecordingOpsAlertPort;
import com.homefix.invoice.support.TestPorts.RecordingStoragePort;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InvoiceService} orchestration (Requirement 13): signed-URL 72-hour expiry
 * (13.3), PDF-generation retry up to the configured limit and ops alert on exhaustion without
 * blocking the flow (13.7), idempotent handling of a redelivered payment, and invoice-number
 * uniqueness across generated invoices (13.4).
 *
 * <p>Pure in-memory fakes; no Spring context, database, or network.
 */
class InvoiceServiceTest {

    private static final Instant NOW = Instant.parse("2024-07-15T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final InMemoryInvoiceRepository invoiceRepository = new InMemoryInvoiceRepository();
    private final InvoiceNumberGenerator numberGenerator =
            new InvoiceNumberGenerator(new InMemoryInvoiceSequenceRepository());
    private final InvoiceDetailsPort detailsPort = new TestPorts.FixedDetailsPort();
    private final RecordingStoragePort storagePort = new RecordingStoragePort(FIXED_CLOCK);
    private final RecordingNotificationPort notificationPort = new RecordingNotificationPort();
    private final RecordingOpsAlertPort opsAlertPort = new RecordingOpsAlertPort();
    private final InvoiceProperties properties = properties();

    private InvoiceService serviceWith(com.homefix.invoice.pdf.PdfGeneratorPort pdf) {
        // No-op sleeper so retry backoff does not slow the test.
        return new InvoiceService(numberGenerator, invoiceRepository, detailsPort, pdf, storagePort,
                notificationPort, opsAlertPort, properties, FIXED_CLOCK, millis -> {});
    }

    @Test
    void deliversSignedUrlWith72HourExpiryAfterGeneration() {
        InvoiceService service = serviceWith(new FlakyPdfGenerator(0));

        Invoice invoice = service.generateForPayment(event(UUID.randomUUID()));

        assertThat(invoice).isNotNull();
        assertThat(invoice.getInvoiceNumber()).matches(InvoiceNumberGenerator.FORMAT);
        assertThat(storagePort.stores).isEqualTo(1);
        assertThat(notificationPort.deliveries).isEqualTo(1);
        // Requirement 13.3: signed URL expires 72 hours after issuance.
        assertThat(storagePort.lastTtl).isEqualTo(Duration.ofHours(72));
        assertThat(notificationPort.lastExpiresAt).isEqualTo(NOW.plus(Duration.ofHours(72)));
        assertThat(opsAlertPort.alerts).isZero();
    }

    @Test
    void retriesPdfGenerationAndSucceedsBeforeExhaustion() {
        FlakyPdfGenerator pdf = new FlakyPdfGenerator(2); // fail twice, succeed on the 3rd attempt
        InvoiceService service = serviceWith(pdf);

        Invoice invoice = service.generateForPayment(event(UUID.randomUUID()));

        assertThat(invoice).isNotNull();
        assertThat(pdf.callCount()).isEqualTo(3);
        assertThat(storagePort.stores).isEqualTo(1);
        assertThat(notificationPort.deliveries).isEqualTo(1);
        assertThat(opsAlertPort.alerts).isZero();
    }

    @Test
    void alertsOpsAndDoesNotBlockWhenPdfRetriesAreExhausted() {
        AlwaysFailingPdfGenerator pdf = new AlwaysFailingPdfGenerator();
        InvoiceService service = serviceWith(pdf);
        PaymentCompletedEvent event = event(UUID.randomUUID());

        Invoice invoice = service.generateForPayment(event); // must not throw (Requirement 13.7)

        assertThat(invoice).isNull();
        assertThat(pdf.callCount()).isEqualTo(properties.getMaxPdfRetries());
        assertThat(opsAlertPort.alerts).isEqualTo(1);
        assertThat(opsAlertPort.lastBookingId).isEqualTo(event.bookingId());
        assertThat(opsAlertPort.lastPaymentId).isEqualTo(event.paymentId());
        // Nothing persisted or delivered on exhaustion.
        assertThat(storagePort.stores).isZero();
        assertThat(notificationPort.deliveries).isZero();
        assertThat(invoiceRepository.count()).isZero();
    }

    @Test
    void skipsGenerationForARedeliveredPayment() {
        InvoiceService service = serviceWith(new FlakyPdfGenerator(0));
        UUID paymentId = UUID.randomUUID();
        PaymentCompletedEvent event = event(paymentId);

        Invoice first = service.generateForPayment(event);
        Invoice second = service.generateForPayment(event); // duplicate delivery

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(invoiceRepository.count()).isEqualTo(1);
        assertThat(storagePort.stores).isEqualTo(1);
        assertThat(notificationPort.deliveries).isEqualTo(1);
    }

    @Test
    void distinctPaymentsReceiveDistinctInvoiceNumbers() {
        InvoiceService service = serviceWith(new FlakyPdfGenerator(0));

        Invoice a = service.generateForPayment(event(UUID.randomUUID()));
        Invoice b = service.generateForPayment(event(UUID.randomUUID()));

        assertThat(a.getInvoiceNumber()).isNotEqualTo(b.getInvoiceNumber());
        assertThat(invoiceRepository.count()).isEqualTo(2);
    }

    private static PaymentCompletedEvent event(UUID paymentId) {
        return new PaymentCompletedEvent(
                paymentId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("590.00"),
                new BigDecimal("118.00"),
                new BigDecimal("472.00"),
                "UPI",
                NOW);
    }

    private static InvoiceProperties properties() {
        InvoiceProperties p = new InvoiceProperties();
        p.setMaxPdfRetries(3);
        p.setPdfRetryBackoff(Duration.ofMillis(1));
        p.setSignedUrlTtl(Duration.ofHours(72));
        p.setRetention(Duration.ofDays(730));
        p.setTaxIdentifier("GSTIN-TEST");
        return p;
    }
}
