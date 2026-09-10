package com.homefix.pricing.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import com.homefix.pricing.api.GlobalExceptionHandler;
import com.homefix.pricing.api.PricingController;
import com.homefix.pricing.domain.Money;
import com.homefix.pricing.domain.PriceBreakdown;
import com.homefix.pricing.service.PriceQuoteService;
import com.homefix.pricing.service.PriceRequest;
import com.homefix.pricing.service.PricingConfigService;
import com.homefix.pricing.service.ProviderOverrideService;

import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import au.com.dius.pact.provider.spring.junit5.MockMvcTestTarget;

/**
 * Provider-side verification (Task 46) that the Pricing Engine honours the HTTP contract the
 * Booking Service published for {@code POST /pricing/estimate}. The controller is exercised
 * through the real Spring MVC stack via a standalone {@link MockMvcTestTarget} — the same
 * lightweight setup used by {@code PricingControllerTest} — so no server or Spring context is
 * booted.
 *
 * <p>The pact is loaded from {@code src/test/resources/pacts}, which mirrors what CI pulls from
 * the Pact Broker. When {@code PACT_BROKER_BASE_URL} is configured, the CI job swaps this
 * loader for {@code @PactBroker} so the verification result is published back to the broker and
 * can-i-deploy can gate the deployment.
 */
@Provider("pricing-engine")
@PactFolder("src/test/resources/pacts")
@ExtendWith(PactVerificationInvocationContextProvider.class)
class PricingEngineProviderPactTest {

    private PriceQuoteService quoteService;

    @BeforeEach
    void before(PactVerificationContext context) {
        quoteService = Mockito.mock(PriceQuoteService.class);
        ProviderOverrideService overrideService = Mockito.mock(ProviderOverrideService.class);
        PricingConfigService configService = Mockito.mock(PricingConfigService.class);

        PricingController controller = new PricingController(quoteService, overrideService, configService);
        MockMvcTestTarget target = new MockMvcTestTarget();
        target.setControllers(controller);
        target.setControllerAdvices(new GlobalExceptionHandler());
        context.setTarget(target);
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPacts(PactVerificationContext context) {
        context.verifyInteraction();
    }

    /**
     * Provider state for the estimate interaction: a subcategory with pricing parameters exists,
     * so the engine returns a fully itemised, non-null breakdown whose components sum to total.
     */
    @State("a configured subcategory with pricing parameters")
    void configuredSubcategory() {
        PriceBreakdown breakdown = PriceBreakdown.builder()
                .basePrice(new BigDecimal("500.00"))
                .distanceCharge(new BigDecimal("50.00"))
                .platformFee(new BigDecimal("55.00"))
                .taxes(new BigDecimal("99.00"))
                .build(new Money(2, RoundingMode.HALF_UP), new BigDecimal("0.01"));

        when(quoteService.quote(any(PriceRequest.class))).thenReturn(breakdown);
    }
}
