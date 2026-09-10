package com.homefix.invoice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.homefix.invoice.api.dto.InvoiceSummary;
import com.homefix.invoice.api.dto.ProviderEarningsStatement;
import com.homefix.invoice.config.InvoiceProperties;
import com.homefix.invoice.domain.Invoice;
import com.homefix.invoice.support.InMemoryInvoiceRepository;
import com.homefix.invoice.support.TestPorts.RecordingStoragePort;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InvoiceQueryService}: 24-month customer-history retention floor
 * (Requirement 13.5) and provider monthly earnings-statement availability on the first day of the
 * following month (Requirement 13.6).
 */
class InvoiceQueryServiceTest {

    private static final Instant NOW = Instant.parse("2024-07-15T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final InMemoryInvoiceRepository invoiceRepository = new InMemoryInvoiceRepository();
    private final RecordingStoragePort storagePort = new RecordingStoragePort(FIXED_CLOCK);
    private final InvoiceProperties properties = properties();
    private final InvoiceQueryService service =
            new InvoiceQueryService(invoiceRepository, storagePort, properties, FIXED_CLOCK);

    @Test
    void customerHistoryExcludesInvoicesOlderThanRetentionWindow() {
        UUID customer = UUID.randomUUID();
        // Within 24 months.
        Invoice recent = save(customer, NOW.minus(Duration.ofDays(30)), "INV-2024-06-000001");
        // Just outside the 730-day retention window.
        save(customer, NOW.minus(Duration.ofDays(740)), "INV-2022-07-000001");

        List<InvoiceSummary> history = service.customerHistory(customer, 0, 20);

        assertThat(history).extracting(InvoiceSummary::invoiceNumber)
                .containsExactly(recent.getInvoiceNumber());
        assertThat(history.get(0).downloadUrl()).isNotBlank();
        assertThat(history.get(0).downloadUrlExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(72)));
    }

    @Test
    void providerStatementIsUnavailableBeforeFirstDayOfFollowingMonth() {
        UUID provider = UUID.randomUUID();
        // Clock is 2024-07-15: the July statement is not yet available (needs 2024-08-01).
        ProviderEarningsStatement statement = service.providerMonthlyStatement(provider, 2024, 7);

        assertThat(statement.available()).isFalse();
        assertThat(statement.jobCount()).isZero();
        assertThat(statement.netPayout()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void providerStatementAggregatesSettledJobsOnceAvailable() {
        UUID provider = UUID.randomUUID();
        // June 2024 statement is available on 2024-07-01, before the fixed clock of 2024-07-15.
        saveForProvider(provider, Instant.parse("2024-06-10T09:00:00Z"), "INV-2024-06-000001",
                new BigDecimal("590.00"), new BigDecimal("118.00"), new BigDecimal("472.00"));
        saveForProvider(provider, Instant.parse("2024-06-20T09:00:00Z"), "INV-2024-06-000002",
                new BigDecimal("236.00"), new BigDecimal("47.20"), new BigDecimal("188.80"));
        // A July invoice must not leak into the June statement.
        saveForProvider(provider, Instant.parse("2024-07-02T09:00:00Z"), "INV-2024-07-000001",
                new BigDecimal("100.00"), new BigDecimal("20.00"), new BigDecimal("80.00"));

        ProviderEarningsStatement statement = service.providerMonthlyStatement(provider, 2024, 6);

        assertThat(statement.available()).isTrue();
        assertThat(statement.jobCount()).isEqualTo(2);
        assertThat(statement.grossEarnings()).isEqualByComparingTo("826.00");
        assertThat(statement.platformFees()).isEqualByComparingTo("165.20");
        assertThat(statement.netPayout()).isEqualByComparingTo("660.80");
    }

    private Invoice save(UUID customer, Instant generatedAt, String number) {
        Invoice invoice = Invoice.create(number, UUID.randomUUID(), UUID.randomUUID(), customer,
                UUID.randomUUID(), "invoices/" + customer + "/" + number + ".pdf",
                new BigDecimal("100.00"), new BigDecimal("20.00"), new BigDecimal("80.00"), generatedAt);
        return invoiceRepository.save(invoice);
    }

    private void saveForProvider(UUID provider, Instant generatedAt, String number,
                                 BigDecimal gross, BigDecimal fee, BigDecimal net) {
        Invoice invoice = Invoice.create(number, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                provider, "invoices/x/" + number + ".pdf", gross, fee, net, generatedAt);
        invoiceRepository.save(invoice);
    }

    private static InvoiceProperties properties() {
        InvoiceProperties p = new InvoiceProperties();
        p.setSignedUrlTtl(Duration.ofHours(72));
        p.setRetention(Duration.ofDays(730));
        return p;
    }
}
