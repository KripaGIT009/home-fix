package com.homefix.invoice.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.homefix.invoice.api.dto.InvoiceSummary;
import com.homefix.invoice.api.dto.ProviderEarningsStatement;
import com.homefix.invoice.config.InvoiceProperties;
import com.homefix.invoice.domain.Invoice;
import com.homefix.invoice.domain.InvoiceRepository;
import com.homefix.invoice.storage.InvoiceStoragePort;
import com.homefix.invoice.storage.SignedUrl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Read-side queries for the Invoice Service:
 *
 * <ul>
 *   <li>Customer invoice history, retained for at least 24 months (Requirement 13.5). Each
 *       returned summary carries a freshly-minted signed download URL.</li>
 *   <li>Provider monthly earnings statements, available only on/after the first calendar day of
 *       the following month (Requirement 13.6).</li>
 * </ul>
 */
@Service
public class InvoiceQueryService {

    /** Earliest statement year accepted; no invoice can predate the platform. */
    static final int MIN_YEAR = 2000;

    /** Latest statement year accepted; keeps the period's instants well inside the supported range. */
    static final int MAX_YEAR = 9999;

    private final InvoiceRepository invoiceRepository;
    private final InvoiceStoragePort storagePort;
    private final InvoiceProperties properties;
    private final Clock clock;

    // These classes keep a second, package-private constructor so tests can pin the Clock.
    // With more than one constructor Spring will not guess: without @Autowired it falls back
    // to a no-arg constructor that does not exist and the bean fails to instantiate.
    @Autowired
    public InvoiceQueryService(InvoiceRepository invoiceRepository,
                               InvoiceStoragePort storagePort,
                               InvoiceProperties properties) {
        this(invoiceRepository, storagePort, properties, Clock.systemUTC());
    }

    InvoiceQueryService(InvoiceRepository invoiceRepository,
                        InvoiceStoragePort storagePort,
                        InvoiceProperties properties,
                        Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.storagePort = storagePort;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Returns a customer's invoice history newest-first, bounded to the retention window
     * (Requirement 13.5). Each summary includes a fresh signed URL.
     */
    public List<InvoiceSummary> customerHistory(UUID customerId, int page, int size) {
        Instant since = clock.instant().minus(properties.getRetention());
        Pageable pageable = PageRequest.of(Math.max(0, page), clampSize(size));
        List<Invoice> invoices = invoiceRepository
                .findByCustomerIdAndGeneratedAtGreaterThanEqualOrderByGeneratedAtDesc(
                        customerId, since, pageable);
        return invoices.stream().map(this::toSummary).toList();
    }

    /**
     * Builds a provider's earnings statement for the given month. The statement is only
     * {@code available} on/after the first day of the following month (Requirement 13.6); before
     * then an empty, unavailable statement is returned.
     *
     * @throws InvoiceException 400 {@code VALIDATION_ERROR} for a month outside 1-12 or a year outside
     *                          {@value #MIN_YEAR}-{@value #MAX_YEAR}. {@link YearMonth#of} would
     *                          otherwise throw a {@code DateTimeException} no handler maps, which
     *                          reached the client as a 500.
     */
    public ProviderEarningsStatement providerMonthlyStatement(UUID providerId, int year, int month) {
        if (month < 1 || month > 12) {
            throw InvoiceException.validation("month must be between 1 and 12, was " + month);
        }
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw InvoiceException.validation(
                    "year must be between " + MIN_YEAR + " and " + MAX_YEAR + ", was " + year);
        }
        YearMonth period = YearMonth.of(year, month);
        Instant from = period.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = period.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        boolean available = !clock.instant().isBefore(to);
        if (!available) {
            return new ProviderEarningsStatement(providerId, year, month, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
        }

        List<Invoice> invoices = invoiceRepository
                .findByProviderIdAndGeneratedAtGreaterThanEqualAndGeneratedAtLessThanOrderByGeneratedAtDesc(
                        providerId, from, to);

        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        for (Invoice invoice : invoices) {
            gross = gross.add(invoice.getGrossAmount());
            fees = fees.add(invoice.getPlatformFee());
            net = net.add(invoice.getProviderNetEarning());
        }
        return new ProviderEarningsStatement(providerId, year, month, invoices.size(), gross, fees, net, true);
    }

    private InvoiceSummary toSummary(Invoice invoice) {
        SignedUrl url = storagePort.signedUrl(invoice.getS3Key(), properties.getSignedUrlTtl());
        return InvoiceSummary.from(invoice, url.url(), url.expiresAt());
    }

    private static int clampSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, 100);
    }
}
