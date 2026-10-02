package com.homefix.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.homefix.payment.domain.PaymentMethod;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.gateway.RazorpayGatewayAdapter;
import com.homefix.payment.support.InMemoryPaymentTransactionRepository;

/**
 * Unit tests for {@link AdminPaymentQueryService} (Requirement 19.2): the list is newest first and
 * capped, and a search is a case-insensitive substring of the transaction id, booking id or gateway
 * reference in which LIKE wildcards are literal.
 */
class AdminPaymentQueryServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

    private InMemoryPaymentTransactionRepository repository;
    private AdminPaymentQueryService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryPaymentTransactionRepository();
        service = new AdminPaymentQueryService(repository);
    }

    private PaymentTransaction save(UUID bookingId, String gatewayReference, long seconds) {
        PaymentTransaction tx = PaymentTransaction.initiate("key-" + UUID.randomUUID(), UUID.randomUUID(),
                bookingId, UUID.randomUUID(), new BigDecimal("100.00"), new BigDecimal("20.00"),
                PaymentMethod.UPI, RazorpayGatewayAdapter.GATEWAY_ID, null);
        tx.setGatewayReference(gatewayReference);
        ReflectionTestUtils.setField(tx, "createdAt", T0.plusSeconds(seconds));
        return repository.save(tx);
    }

    @Test
    void listsNewestFirst() {
        PaymentTransaction older = save(UUID.randomUUID(), null, 1);
        PaymentTransaction newer = save(UUID.randomUUID(), null, 2);

        assertThat(service.search(null)).containsExactly(newer, older);
        assertThat(service.search("   ")).containsExactly(newer, older);
    }

    @Test
    void searchMatchesTransactionIdBookingIdAndGatewayReference() {
        UUID booking = UUID.fromString("abcdef12-0000-4000-8000-000000000001");
        PaymentTransaction byBooking = save(booking, null, 1);
        PaymentTransaction byReference = save(UUID.randomUUID(), "pay_XYZ789", 2);
        PaymentTransaction byId = save(UUID.randomUUID(), null, 3);

        assertThat(service.search("ABCDEF12")).containsExactly(byBooking);
        assertThat(service.search(" xyz7 ")).containsExactly(byReference);
        assertThat(service.search(byId.getId().toString().toUpperCase())).containsExactly(byId);
        assertThat(service.search("no-such-thing")).isEmpty();
    }

    @Test
    void likeWildcardsInTheSearchAreLiteral() {
        save(UUID.randomUUID(), "payXabc", 1);
        PaymentTransaction underscore = save(UUID.randomUUID(), "pay_abc", 2);

        assertThat(service.search("pay_")).containsExactly(underscore);
        assertThat(service.search("%")).isEmpty();
    }

    @Test
    void isCappedAtTheAdminListLimitKeepingTheNewest() {
        for (int i = 0; i < AdminPaymentQueryService.ADMIN_LIST_LIMIT + 3; i++) {
            save(UUID.randomUUID(), null, i);
        }

        List<PaymentTransaction> result = service.search(null);

        assertThat(result).hasSize(AdminPaymentQueryService.ADMIN_LIST_LIMIT);
        assertThat(result.get(0).getCreatedAt())
                .isEqualTo(T0.plusSeconds(AdminPaymentQueryService.ADMIN_LIST_LIMIT + 2));
    }

    @Test
    void likePatternEscapesWildcardsAndTheEscapeCharacter() {
        assertThat(AdminPaymentQueryService.likePattern("")).isEqualTo("%%");
        assertThat(AdminPaymentQueryService.likePattern("A_b%c!")).isEqualTo("%a!_b!%c!!%");
    }
}
