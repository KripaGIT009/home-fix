package com.homefix.app;

import com.homefix.auth.AuthServiceApplication;
import com.homefix.customer.CustomerServiceApplication;
import com.homefix.host.HostedService;
import com.homefix.host.ServiceHost;
import com.homefix.provider.ProviderServiceApplication;
import com.homefix.verification.VerificationServiceApplication;

/**
 * Identity and people: sign-in, customers, providers and agencies, verification.
 *
 * <p>Runs 4 services in one JVM, each in its own Spring context on its own port; see
 * {@link ServiceHost}.
 */
public final class IdentityApplication {

    private IdentityApplication() {
    }

    public static void main(String[] args) {
        ServiceHost.run(args,
                HostedService.of("auth-service", AuthServiceApplication.class, 8081),
                HostedService.of("customer-service", CustomerServiceApplication.class, 8082),
                HostedService.of("provider-service", ProviderServiceApplication.class, 8083),
                HostedService.of("verification-service", VerificationServiceApplication.class, 8094));
    }
}
