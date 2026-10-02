package com.homefix.pricing.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.pricing.config.PromotionClientProperties;
import com.homefix.pricing.service.PricingException;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests {@link PromotionCouponDiscountAdapter} against a loopback stub of the Promotion Service,
 * the same pattern the Dispatch Engine uses for its HTTP adapters: the request it sends (path, query,
 * service credential), and how each kind of answer is reported to the estimate caller — a discount,
 * a 422 for a coupon that does not apply, or a 503 whenever the Promotion Service was not usefully
 * consulted. The last group matters most: none of them may surface as a 500.
 */
class PromotionCouponDiscountAdapterTest {

    private static final String KEY = "test-internal-key";
    private static final BigDecimal ORDER = new BigDecimal("150.00");

    private HttpServer server;
    private String baseUrl;
    private final List<String> requestUris = new CopyOnWriteArrayList<>();
    private final List<String> presentedKeys = new CopyOnWriteArrayList<>();
    private volatile int statusToReturn = 200;
    private volatile String bodyToReturn = "{}";
    private volatile long delayMillis = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestUris.add(exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null
                            ? "" : "?" + exchange.getRequestURI().getRawQuery()));
            String key = exchange.getRequestHeaders()
                    .getFirst(PromotionCouponDiscountAdapter.INTERNAL_KEY_HEADER);
            presentedKeys.add(key == null ? "<none>" : key);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = bodyToReturn.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusToReturn, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private PromotionCouponDiscountAdapter adapter() {
        return adapter(p -> { });
    }

    private PromotionCouponDiscountAdapter adapter(Consumer<PromotionClientProperties> tweak) {
        PromotionClientProperties properties = new PromotionClientProperties();
        properties.setBaseUrl(baseUrl);
        properties.setInternalApiKey(KEY);
        properties.setConnectTimeout(Duration.ofMillis(500));
        properties.setReadTimeout(Duration.ofMillis(500));
        tweak.accept(properties);
        return new PromotionCouponDiscountAdapter(properties);
    }

    private static void assertPricingError(Throwable thrown, int status, String errorCode) {
        assertThat(thrown).isInstanceOf(PricingException.class);
        PricingException ex = (PricingException) thrown;
        assertThat(ex.getStatus().value()).isEqualTo(status);
        assertThat(ex.getErrorCode()).isEqualTo(errorCode);
    }

    // ---- Successful quotes -----------------------------------------------------------------------

    @Test
    void quote_returnsTheDiscountAndPresentsTheServiceCredential() {
        UUID user = UUID.randomUUID();
        bodyToReturn = "{\"couponId\":\"" + UUID.randomUUID()
                + "\",\"code\":\"SAVE20\",\"discountAmount\":20.00}";

        BigDecimal discount = adapter().discountFor("SAVE20", user, ORDER);

        assertThat(discount).isEqualByComparingTo("20.00");
        assertThat(requestUris).containsExactly(
                "/internal/coupons/SAVE20/quote?orderValue=150.00&userId=" + user);
        assertThat(presentedKeys).containsExactly(KEY);
    }

    @Test
    void quote_withoutAUser_omitsTheUserParameter() {
        bodyToReturn = "{\"discountAmount\":5}";

        adapter().discountFor("SAVE5", null, ORDER);

        assertThat(requestUris).containsExactly("/internal/coupons/SAVE5/quote?orderValue=150.00");
    }

    @Test
    void quote_encodesTheCodeAsASinglePathSegment() {
        bodyToReturn = "{\"discountAmount\":5}";

        adapter().discountFor("A/B C", null, ORDER);

        assertThat(requestUris).singleElement().asString()
                .startsWith("/internal/coupons/A%2FB%20C/quote?");
    }

    @Test
    void quote_aboveTheOrderValue_isClampedToIt() {
        bodyToReturn = "{\"discountAmount\":500.00}";

        assertThat(adapter().discountFor("BIG", null, ORDER)).isEqualByComparingTo("150.00");
    }

    // ---- The coupon does not apply: 422 ----------------------------------------------------------

    @Test
    void unknownCode_is422CouponNotFound() {
        statusToReturn = 404;
        bodyToReturn = "{\"errorCode\":\"COUPON_NOT_FOUND\",\"message\":\"Coupon with code 'NOPE' not found\"}";

        Throwable thrown = catchThrowable(() -> adapter().discountFor("NOPE", null, ORDER));

        assertPricingError(thrown, 422, "COUPON_NOT_FOUND");
        assertThat(thrown).hasMessageContaining("NOPE");
    }

    @Test
    void violatedConstraint_passesThePromotionServiceCodeAndMessageThrough() {
        statusToReturn = 422;
        bodyToReturn = "{\"errorCode\":\"COUPON_EXPIRED\",\"message\":\"Coupon 'OLD10' expired on 2026-01-01\"}";

        Throwable thrown = catchThrowable(() -> adapter().discountFor("OLD10", null, ORDER));

        assertPricingError(thrown, 422, "COUPON_EXPIRED");
        assertThat(thrown).hasMessage("Coupon 'OLD10' expired on 2026-01-01");
    }

    @Test
    void a422WithoutAnErrorCode_isStillA422() {
        statusToReturn = 422;
        bodyToReturn = "";

        assertPricingError(catchThrowable(() -> adapter().discountFor("X1", null, ORDER)),
                422, PromotionCouponDiscountAdapter.COUPON_NOT_APPLICABLE);
    }

    // ---- The Promotion Service could not be consulted: 503 ---------------------------------------

    @Test
    void bare404WithoutTheErrorCode_isUnavailableNotUnknown() {
        // A wrong base URL, or a Promotion Service build without the endpoint, must not reject
        // every coupon as non-existent.
        statusToReturn = 404;
        bodyToReturn = "{\"status\":404,\"error\":\"Not Found\"}";

        assertPricingError(catchThrowable(() -> adapter().discountFor("SAVE20", null, ORDER)),
                503, "COUPON_SERVICE_UNAVAILABLE");
    }

    @Test
    void serverError_isUnavailableAndNotRetried() {
        statusToReturn = 500;
        bodyToReturn = "{\"error\":\"boom\"}";

        assertPricingError(catchThrowable(() -> adapter().discountFor("SAVE20", null, ORDER)),
                503, "COUPON_SERVICE_UNAVAILABLE");
        // The estimate's caller retries a 5xx; retrying here as well would multiply the attempts.
        assertThat(requestUris).hasSize(1);
    }

    @Test
    void refusedCredential_isUnavailable() {
        statusToReturn = 401;
        bodyToReturn = "{\"errorCode\":\"INTERNAL_AUTH_FAILED\",\"message\":\"Invalid internal credentials\"}";

        assertPricingError(catchThrowable(() -> adapter().discountFor("SAVE20", null, ORDER)),
                503, "COUPON_SERVICE_UNAVAILABLE");
    }

    @Test
    void successWithoutADiscount_isUnavailable() {
        bodyToReturn = "{\"code\":\"SAVE20\"}";

        assertPricingError(catchThrowable(() -> adapter().discountFor("SAVE20", null, ORDER)),
                503, "COUPON_SERVICE_UNAVAILABLE");
    }

    @Test
    void slowResponse_timesOutPromptlyAsUnavailable() {
        delayMillis = 2_000;
        PromotionCouponDiscountAdapter adapter = adapter(p -> p.setReadTimeout(Duration.ofMillis(200)));

        long started = System.nanoTime();
        Throwable thrown = catchThrowable(() -> adapter.discountFor("SAVE20", null, ORDER));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertPricingError(thrown, 503, "COUPON_SERVICE_UNAVAILABLE");
        assertThat(elapsedMillis).isLessThan(1_500);
    }

    @Test
    void unreachableService_isUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        PromotionCouponDiscountAdapter adapter =
                adapter(p -> p.setBaseUrl("http://127.0.0.1:" + closedPort));

        assertPricingError(catchThrowable(() -> adapter.discountFor("SAVE20", null, ORDER)),
                503, "COUPON_SERVICE_UNAVAILABLE");
    }

    @Test
    void missingCredential_failsCouponQuotesWithoutCallingOut() {
        for (String configured : new String[] {null, "", "   "}) {
            PromotionCouponDiscountAdapter adapter = adapter(p -> p.setInternalApiKey(configured));

            assertPricingError(catchThrowable(() -> adapter.discountFor("SAVE20", null, ORDER)),
                    503, "COUPON_SERVICE_UNAVAILABLE");
        }
        assertThat(requestUris).isEmpty();
    }
}
