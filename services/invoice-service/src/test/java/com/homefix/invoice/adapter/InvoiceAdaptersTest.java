package com.homefix.invoice.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import com.homefix.invoice.alert.LoggingOpsAlertAdapter;
import com.homefix.invoice.config.InvoiceProperties;
import com.homefix.invoice.details.InvoiceDetails;
import com.homefix.invoice.details.StubInvoiceDetailsAdapter;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Invoice Service outbound adapters and configuration properties. Verifies the
 * deterministic details stub, the ops-alert logging adapter, and the tunable properties. Storage
 * signed-URL math is covered in {@code InMemoryInvoiceStorageAdapterTest} (same package as the
 * adapter, which needs its package-private clock constructor).
 */
class InvoiceAdaptersTest {

    @Test
    void stubDetails_returnsDeterministicDetailsForTheBooking() {
        UUID bookingId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();

        InvoiceDetails details = new StubInvoiceDetailsAdapter().resolve(bookingId, customerId, providerId);

        assertThat(details.customerName()).contains(customerId.toString());
        assertThat(details.providerName()).contains(providerId.toString());
        assertThat(details.serviceDescription()).contains(bookingId.toString());
        assertThat(details.lineItems()).isNotEmpty();
    }

    @Test
    void loggingOpsAlert_alertInvoiceGenerationFailed_doesNotThrow() {
        new LoggingOpsAlertAdapter()
                .alertInvoiceGenerationFailed(UUID.randomUUID(), UUID.randomUUID(), "PDF exhausted");
    }

    @Test
    void invoiceProperties_expose_tunableDefaultsAndNestedConfig() {
        InvoiceProperties p = new InvoiceProperties();
        assertThat(p.getMaxPdfRetries()).isEqualTo(3);
        assertThat(p.getSignedUrlTtl()).isEqualTo(Duration.ofHours(72));
        assertThat(p.getRetention()).isEqualTo(Duration.ofDays(730));
        assertThat(p.getTaxIdentifier()).isNotBlank();
        assertThat(p.getPdfRetryBackoff()).isPositive();
        assertThat(p.getS3().getBackend()).isEqualTo("memory");
        assertThat(p.getTopics().getPaymentCompleted()).isEqualTo("PaymentCompleted");
        assertThat(p.getClients().getNotificationServiceBaseUrl()).isNotBlank();

        // Setters round-trip.
        p.setMaxPdfRetries(5);
        p.setPdfRetryBackoff(Duration.ofSeconds(1));
        p.setSignedUrlTtl(Duration.ofHours(24));
        p.setRetention(Duration.ofDays(365));
        p.setTaxIdentifier("GSTIN-X");
        InvoiceProperties.Topics topics = new InvoiceProperties.Topics();
        topics.setPaymentCompleted("PC");
        p.setTopics(topics);
        InvoiceProperties.S3 s3 = new InvoiceProperties.S3();
        s3.setBucket("b");
        s3.setRegion("r");
        s3.setBackend("aws");
        p.setS3(s3);
        InvoiceProperties.Clients clients = new InvoiceProperties.Clients();
        clients.setNotificationServiceBaseUrl("http://notif");
        p.setClients(clients);

        assertThat(p.getMaxPdfRetries()).isEqualTo(5);
        assertThat(p.getSignedUrlTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(p.getTopics().getPaymentCompleted()).isEqualTo("PC");
        assertThat(p.getS3().getBucket()).isEqualTo("b");
        assertThat(p.getS3().getRegion()).isEqualTo("r");
        assertThat(p.getS3().getBackend()).isEqualTo("aws");
        assertThat(p.getClients().getNotificationServiceBaseUrl()).isEqualTo("http://notif");
    }
}
