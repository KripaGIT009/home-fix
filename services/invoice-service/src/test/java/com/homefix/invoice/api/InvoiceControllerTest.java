package com.homefix.invoice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.homefix.invoice.api.dto.InvoiceSummary;
import com.homefix.invoice.api.dto.ProviderEarningsStatement;
import com.homefix.invoice.service.InvoiceQueryService;
import org.junit.jupiter.api.Test;

/**
 * Direct-invocation tests for {@link InvoiceController}. The controller is a thin delegate over
 * {@link InvoiceQueryService}; these tests prove the delegation and parameter forwarding for the
 * customer-history and provider-statement endpoints (Requirements 13.5, 13.6). Standalone MockMvc
 * is not used because the build does not enable the {@code -parameters} compiler flag needed to
 * resolve unnamed {@code @PathVariable}s.
 */
class InvoiceControllerTest {

    private final InvoiceQueryService queryService = mock(InvoiceQueryService.class);
    private final InvoiceController controller = new InvoiceController(queryService);

    @Test
    void customerHistory_forwardsPagingAndReturnsSummaries() {
        UUID customerId = UUID.randomUUID();
        InvoiceSummary summary = new InvoiceSummary(UUID.randomUUID(), "INV-2024-07-000001",
                UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                "https://example/inv.pdf", Instant.now().plusSeconds(3600));
        when(queryService.customerHistory(customerId, 1, 50)).thenReturn(List.of(summary));

        List<InvoiceSummary> result = controller.customerHistory(customerId, 1, 50);

        assertThat(result).containsExactly(summary);
        verify(queryService).customerHistory(customerId, 1, 50);
    }

    @Test
    void providerStatement_forwardsYearMonthAndReturnsStatement() {
        UUID providerId = UUID.randomUUID();
        ProviderEarningsStatement statement = new ProviderEarningsStatement(providerId, 2024, 6, 2,
                new BigDecimal("826.00"), new BigDecimal("165.20"), new BigDecimal("660.80"), true);
        when(queryService.providerMonthlyStatement(eq(providerId), eq(2024), eq(6)))
                .thenReturn(statement);

        ProviderEarningsStatement result = controller.providerStatement(providerId, 2024, 6);

        assertThat(result).isEqualTo(statement);
        assertThat(result.available()).isTrue();
    }
}
