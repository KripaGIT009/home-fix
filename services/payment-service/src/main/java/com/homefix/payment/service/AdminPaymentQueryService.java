package com.homefix.payment.service;

import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.domain.PaymentTransactionRepository;

/**
 * Read side of the Admin Portal's Payment Management screen (Requirement 19.2): the transactions
 * a Finance_Admin searches before issuing a refund.
 *
 * <p>Kept apart from {@link PaymentService}, which owns every money movement, because a list has
 * none of its concerns — no gateway, no idempotency, no state machine. The refund the screen
 * issues goes through {@link PaymentService#refund} unchanged.
 *
 * <p>Callers are staff only: {@code PaymentRbacConfig} restricts {@code /admin/payments/**} to the
 * finance tier, so there is no ownership filter here.
 */
@Service
public class AdminPaymentQueryService {

    /**
     * Most transactions the list returns. The portal's table takes one bare array with no paging,
     * so the bound is enforced here; a larger result is narrowed by searching.
     */
    public static final int ADMIN_LIST_LIMIT = 200;

    /** LIKE escape character the repository query declares; see {@link #likePattern}. */
    private static final char LIKE_ESCAPE = '!';

    private final PaymentTransactionRepository transactionRepository;

    public AdminPaymentQueryService(PaymentTransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Transactions newest first, at most {@value #ADMIN_LIST_LIMIT}, optionally narrowed to those
     * whose transaction id, booking id or gateway reference contains {@code search}
     * (case-insensitive). A partial id works, so an admin can search by the first characters of a
     * booking id copied from another screen.
     *
     * @param search optional; blank lists everything
     */
    public List<PaymentTransaction> search(String search) {
        String term = search == null ? "" : search.strip();
        return transactionRepository.searchForAdmin(likePattern(term), PageRequest.of(0, ADMIN_LIST_LIMIT));
    }

    /**
     * {@code term} as a lower-case "contains" LIKE pattern. The LIKE wildcards and the escape
     * character itself are escaped, so a search for {@code "50%"} or {@code "pay_"} matches those
     * characters literally instead of acting as a pattern.
     */
    static String likePattern(String term) {
        StringBuilder pattern = new StringBuilder("%");
        for (char c : term.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '%' || c == '_' || c == LIKE_ESCAPE) {
                pattern.append(LIKE_ESCAPE);
            }
            pattern.append(c);
        }
        return pattern.append('%').toString();
    }
}
