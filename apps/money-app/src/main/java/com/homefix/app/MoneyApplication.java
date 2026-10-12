package com.homefix.app;

import com.homefix.host.HostedService;
import com.homefix.host.ServiceHost;
import com.homefix.invoice.InvoiceServiceApplication;
import com.homefix.payment.PaymentServiceApplication;

/**
 * Money: payments, refunds, settlements and invoices.
 *
 * <p>Runs 2 services in one JVM, each in its own Spring context on its own port; see
 * {@link ServiceHost}.
 */
public final class MoneyApplication {

    private MoneyApplication() {
    }

    public static void main(String[] args) {
        ServiceHost.run(args,
                HostedService.of("payment-service", PaymentServiceApplication.class, 8088),
                HostedService.of("invoice-service", InvoiceServiceApplication.class, 8089));
    }
}
