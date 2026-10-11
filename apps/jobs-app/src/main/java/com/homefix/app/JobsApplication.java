package com.homefix.app;

import com.homefix.booking.BookingServiceApplication;
import com.homefix.catalog.CatalogServiceApplication;
import com.homefix.chat.ChatServiceApplication;
import com.homefix.dispatch.DispatchEngineApplication;
import com.homefix.host.HostedService;
import com.homefix.host.ServiceHost;
import com.homefix.location.LocationServiceApplication;
import com.homefix.pricing.PricingEngineApplication;
import com.homefix.promotion.PromotionServiceApplication;

/**
 * Jobs: the service catalogue, prices and coupons, bookings, dispatch, live location and chat.
 *
 * <p>Runs 7 services in one JVM, each in its own Spring context on its own port; see
 * {@link ServiceHost}.
 */
public final class JobsApplication {

    private JobsApplication() {
    }

    public static void main(String[] args) {
        ServiceHost.run(args,
                HostedService.of("catalog-service", CatalogServiceApplication.class, 8085),
                HostedService.of("pricing-engine", PricingEngineApplication.class, 8086),
                HostedService.of("promotion-service", PromotionServiceApplication.class, 8098),
                HostedService.of("booking-service", BookingServiceApplication.class, 8084),
                HostedService.of("dispatch-engine", DispatchEngineApplication.class, 8087),
                HostedService.of("location-service", LocationServiceApplication.class, 8092),
                HostedService.of("chat-service", ChatServiceApplication.class, 8093));
    }
}
