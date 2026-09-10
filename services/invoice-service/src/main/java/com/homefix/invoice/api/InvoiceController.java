package com.homefix.invoice.api;

import java.util.List;
import java.util.UUID;

import com.homefix.invoice.api.dto.InvoiceSummary;
import com.homefix.invoice.api.dto.ProviderEarningsStatement;
import com.homefix.invoice.service.InvoiceQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only REST surface for the Invoice Service.
 *
 * <ul>
 *   <li>{@code GET /invoices/customers/{customerId}} — customer invoice history within the
 *       24-month retention window (Requirement 13.5).</li>
 *   <li>{@code GET /invoices/providers/{providerId}/statements/{year}/{month}} — provider monthly
 *       earnings statement, available on the first day of the following month
 *       (Requirement 13.6).</li>
 * </ul>
 *
 * <p>Authentication is enforced by the shared filters; fine-grained role checks are applied by the
 * shared {@code RbacEnforcementFilter} (Task 4).
 */
@RestController
@RequestMapping("/invoices")
public class InvoiceController {

    private final InvoiceQueryService invoiceQueryService;

    public InvoiceController(InvoiceQueryService invoiceQueryService) {
        this.invoiceQueryService = invoiceQueryService;
    }

    @GetMapping("/customers/{customerId}")
    public List<InvoiceSummary> customerHistory(@PathVariable UUID customerId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return invoiceQueryService.customerHistory(customerId, page, size);
    }

    @GetMapping("/providers/{providerId}/statements/{year}/{month}")
    public ProviderEarningsStatement providerStatement(@PathVariable UUID providerId,
                                                        @PathVariable int year,
                                                        @PathVariable int month) {
        return invoiceQueryService.providerMonthlyStatement(providerId, year, month);
    }
}
