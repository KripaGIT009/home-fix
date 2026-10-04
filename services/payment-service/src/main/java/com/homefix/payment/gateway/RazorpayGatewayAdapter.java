package com.homefix.payment.gateway;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Razorpay {@link PaymentGatewayPort} adapter (Requirement 12.1), against the Razorpay REST API
 * ({@code https://api.razorpay.com/v1}) with the account's key id and key secret.
 *
 * <p>Razorpay takes a payment in three steps, and this adapter supplies the server side of each:
 * <ol>
 *   <li>{@link #charge} creates a Razorpay <em>Order</em> for the transaction's amount (in paise) and
 *       returns its id as the gateway reference. Nothing is charged yet: the customer pays that order
 *       in Razorpay Checkout in the browser, for exactly the amount set here.</li>
 *   <li>Checkout hands the browser {@code razorpay_payment_id}, {@code razorpay_order_id} and
 *       {@code razorpay_signature}; {@link #verifyCheckoutSignature} checks the signature
 *       (HMAC-SHA256 of {@code order_id|payment_id} with the key secret), and {@link #fetchPayment}
 *       / {@link #capture} confirm the payment with Razorpay itself.</li>
 *   <li>Razorpay's webhook ({@code payment.captured}) is verified by
 *       {@link #verifyCallbackSignature}: HMAC-SHA256 of the raw body with the webhook secret.</li>
 * </ol>
 * Refunds go to Razorpay too ({@link #refund}). Settlement transfers to providers are not part of
 * the Razorpay payments API (they are RazorpayX payouts), so {@link #transfer} keeps the inherited
 * simulated behaviour.
 *
 * <p>Without a key id and secret the adapter stays registered (it is the production default gateway,
 * and its webhook secret is still checked) but reports itself not {@linkplain #isReady() ready}, so a
 * booking payment through it is refused with 503 before anything is recorded or charged.
 */
@Component
public class RazorpayGatewayAdapter extends AbstractHmacGatewayAdapter {

    private static final Logger log = LoggerFactory.getLogger(RazorpayGatewayAdapter.class);

    public static final String GATEWAY_ID = "razorpay";

    /** Razorpay accounts here settle in rupees; amounts go to Razorpay in paise. */
    public static final String CURRENCY = "INR";

    private static final BigDecimal PAISE_PER_RUPEE = new BigDecimal("100");

    private final String keyId;
    private final String keySecret;
    private final RestClient restClient;

    @Autowired
    public RazorpayGatewayAdapter(
            @Value("${homefix.payment.gateways.razorpay.key-id:}") String keyId,
            @Value("${homefix.payment.gateways.razorpay.key-secret:}") String keySecret,
            @Value("${homefix.payment.gateways.razorpay.webhook-secret}") String webhookSecret,
            @Value("${homefix.payment.gateways.razorpay.api-base-url:https://api.razorpay.com/v1}")
            String apiBaseUrl) {
        this(keyId, keySecret, webhookSecret, RestClient.builder()
                .baseUrl(apiBaseUrl)
                .requestFactory(requestFactory()));
    }

    /** Lets tests bind a mock server to the builder. */
    RazorpayGatewayAdapter(String keyId, String keySecret, String webhookSecret, RestClient.Builder builder) {
        super(webhookSecret);
        this.keyId = keyId == null ? "" : keyId.trim();
        this.keySecret = keySecret == null ? "" : keySecret.trim();
        this.restClient = builder
                .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth(this.keyId, this.keySecret))
                .build();
        if (isReady()) {
            log.info("Razorpay gateway configured with key {} ({} mode)", this.keyId,
                    this.keyId.startsWith("rzp_live_") ? "LIVE" : "test");
        } else {
            log.warn("Razorpay key id/secret (RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET) are not set; payments "
                    + "through the '{}' gateway are refused until they are", GATEWAY_ID);
        }
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return factory;
    }

    private static String basicAuth(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String gatewayId() {
        return GATEWAY_ID;
    }

    @Override
    public boolean isReady() {
        return !keyId.isEmpty() && !keySecret.isEmpty();
    }

    /** The public key id Checkout is opened with. Not a secret. */
    public String keyId() {
        return keyId;
    }

    /**
     * Creates the Razorpay order the customer then pays in Checkout. The HomeFix transaction id is the
     * order's receipt and is repeated in its notes, so the order can always be traced back.
     */
    @Override
    public GatewayChargeResult charge(GatewayChargeRequest request) {
        requireReady();
        JsonNode order = restClient.post()
                .uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "amount", toPaise(request.amount()),
                        "currency", CURRENCY,
                        "receipt", request.transactionId().toString(),
                        "notes", Map.of(
                                "transactionId", request.transactionId().toString(),
                                "bookingId", request.bookingId().toString())))
                .retrieve()
                .body(JsonNode.class);
        String orderId = text(order, "id");
        if (orderId == null) {
            throw new IllegalStateException("Razorpay created an order without an id");
        }
        log.info("Razorpay order {} created for transaction {} ({} paise)",
                orderId, request.transactionId(), toPaise(request.amount()));
        return new GatewayChargeResult(orderId, true);
    }

    /**
     * Checks the signature Checkout returns with a successful payment: HMAC-SHA256 of
     * {@code order_id|payment_id} with the key secret, compared in constant time.
     */
    public boolean verifyCheckoutSignature(String orderId, String paymentId, String signature) {
        if (!isReady() || orderId == null || paymentId == null || signature == null) {
            return false;
        }
        return HmacSignatures.verify(keySecret, orderId + "|" + paymentId, signature);
    }

    /** Reads a payment from Razorpay, the authority on its order, amount and status. */
    public RazorpayPayment fetchPayment(String paymentId) {
        requireReady();
        JsonNode payment = restClient.get()
                .uri("/payments/{id}", paymentId)
                .retrieve()
                .body(JsonNode.class);
        return RazorpayPayment.from(payment);
    }

    /**
     * Captures an authorized payment, for an account that does not capture automatically. The amount
     * must be the full authorized amount.
     */
    public RazorpayPayment capture(String paymentId, long amountPaise) {
        requireReady();
        JsonNode payment = restClient.post()
                .uri("/payments/{id}/capture", paymentId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", amountPaise, "currency", CURRENCY))
                .retrieve()
                .body(JsonNode.class);
        return RazorpayPayment.from(payment);
    }

    /**
     * Refunds against the order's captured payment.
     *
     * <p>Razorpay refunds a <em>payment</em>, and the transaction holds the order id, so the order's
     * captured payment is looked up first. The HomeFix refund id is sent as the refund's
     * {@code receipt}, and an existing refund with that receipt is returned instead of refunding
     * again, so re-sending a refund whose outcome was unknown cannot refund twice. A request Razorpay
     * refuses (4xx) is a rejection; anything else (timeout, 5xx) is thrown as an unknown outcome.
     */
    @Override
    public GatewayRefundResult refund(GatewayRefundRequest request) {
        requireReady();
        try {
            Optional<String> paymentId = capturedPaymentOf(request.gatewayChargeReference());
            if (paymentId.isEmpty()) {
                log.error("Razorpay order {} has no captured payment to refund", request.gatewayChargeReference());
                return new GatewayRefundResult(null, false);
            }
            Optional<String> existing = refundWithReceipt(paymentId.get(), request.idempotencyKey());
            if (existing.isPresent()) {
                log.info("Razorpay refund {} for receipt {} already exists; not refunding again",
                        existing.get(), request.idempotencyKey());
                return new GatewayRefundResult(existing.get(), true);
            }
            JsonNode refund = restClient.post()
                    .uri("/payments/{id}/refund", paymentId.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "amount", toPaise(request.amount()),
                            "receipt", request.idempotencyKey(),
                            "notes", Map.of("refundId", request.idempotencyKey())))
                    .retrieve()
                    .body(JsonNode.class);
            return new GatewayRefundResult(text(refund, "id"), true);
        } catch (HttpClientErrorException e) {
            log.error("Razorpay refused the refund for order {}: {} {}", request.gatewayChargeReference(),
                    e.getStatusCode(), e.getResponseBodyAsString());
            return new GatewayRefundResult(null, false);
        }
    }

    private Optional<String> capturedPaymentOf(String orderId) {
        JsonNode payments = restClient.get()
                .uri("/orders/{id}/payments", orderId)
                .retrieve()
                .body(JsonNode.class);
        if (payments != null && payments.path("items").isArray()) {
            for (JsonNode item : payments.path("items")) {
                String status = text(item, "status");
                // A fully refunded payment reads "refunded"; it was captured all the same.
                if ("captured".equals(status) || "refunded".equals(status)) {
                    return Optional.ofNullable(text(item, "id"));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> refundWithReceipt(String paymentId, String receipt) {
        JsonNode refunds = restClient.get()
                .uri("/payments/{id}/refunds", paymentId)
                .retrieve()
                .body(JsonNode.class);
        if (refunds != null && refunds.path("items").isArray()) {
            for (JsonNode item : refunds.path("items")) {
                if (receipt.equals(text(item, "receipt"))) {
                    return Optional.ofNullable(text(item, "id"));
                }
            }
        }
        return Optional.empty();
    }

    private void requireReady() {
        if (!isReady()) {
            throw new IllegalStateException("Razorpay is not configured (RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET)");
        }
    }

    /** Rupees to paise. Amounts are validated to two decimal places, so this is exact. */
    public static long toPaise(BigDecimal amount) {
        return amount.multiply(PAISE_PER_RUPEE).longValueExact();
    }

    /** Paise to rupees, at the two-decimal scale every money column uses. */
    public static BigDecimal fromPaise(long paise) {
        return BigDecimal.valueOf(paise, 2);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * The parts of a Razorpay payment entity this service relies on.
     *
     * @param status Razorpay's payment status: {@code created}, {@code authorized}, {@code captured},
     *               {@code refunded} or {@code failed}
     */
    public record RazorpayPayment(String id, String orderId, String status, long amountPaise,
                                  String currency, Instant createdAt) {

        public static RazorpayPayment from(JsonNode entity) {
            if (entity == null || text(entity, "id") == null) {
                throw new IllegalStateException("Razorpay returned no payment");
            }
            JsonNode created = entity.get("created_at");
            return new RazorpayPayment(
                    text(entity, "id"),
                    text(entity, "order_id"),
                    text(entity, "status"),
                    entity.path("amount").asLong(),
                    text(entity, "currency"),
                    created != null && created.canConvertToLong() ? Instant.ofEpochSecond(created.asLong()) : null);
        }

        public boolean isCaptured() {
            return "captured".equals(status);
        }

        public boolean isAuthorized() {
            return "authorized".equals(status);
        }
    }
}
