package com.homefix.payment.support;

import java.util.ArrayList;
import java.util.List;

import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.event.PaymentCompletedPublisher;

/**
 * Test double for {@link PaymentCompletedPublisher} that records published transactions instead of
 * writing an outbox row, so {@code PaymentService} can be tested without a database or an active
 * transaction. Overrides {@code publish} and never invokes the outbox-backed super logic.
 */
public class RecordingPaymentCompletedPublisher extends PaymentCompletedPublisher {

    private final List<PaymentTransaction> published = new ArrayList<>();

    public RecordingPaymentCompletedPublisher() {
        // No outbox publisher needed: publish() is fully overridden below.
        super(null);
    }

    @Override
    public void publish(PaymentTransaction tx) {
        published.add(tx);
    }

    public List<PaymentTransaction> published() {
        return published;
    }
}
