package com.homefix.promotion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.homefix.promotion.config.PromotionProperties;
import com.homefix.promotion.domain.Coupon;
import com.homefix.promotion.domain.DiscountType;
import com.homefix.promotion.service.CouponCounterTransaction;
import com.homefix.promotion.service.CouponService;
import com.homefix.promotion.service.CreateCouponCommand;
import com.homefix.promotion.support.InMemoryCouponRepository;
import com.homefix.promotion.support.InMemoryCouponUsageRepository;

/**
 * Web-layer tests for {@link InternalCouponController}, the Pricing Engine's coupon quote. Runs
 * through standalone MockMvc over a real {@link CouponService} on the in-memory repositories, so the
 * response shape and status codes the Pricing Engine's adapter parses are the ones the service
 * really produces: the discount on success, 404 {@code COUPON_NOT_FOUND}, and 422 with the violated
 * constraint's code. The credential guard on {@code /internal/**} is covered by
 * {@code InternalApiKeyFilterTest}.
 */
class InternalCouponControllerTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2024, 6, 1);
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final InMemoryCouponRepository coupons = new InMemoryCouponRepository();
    private final InMemoryCouponUsageRepository usages = new InMemoryCouponUsageRepository();
    private CouponService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = new CouponService(coupons, usages, new CouponCounterTransaction(coupons, usages),
                new PromotionProperties(), Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new InternalCouponController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** FLAT 50 off, minimum order 100, one use per customer, three in total. */
    private Coupon flat50() {
        return service.createCoupon(new CreateCouponCommand("SAVE50", DiscountType.FLAT,
                new BigDecimal("50"), new BigDecimal("100"), null,
                TODAY.minusDays(1), TODAY.plusDays(30), 1, 3));
    }

    @Test
    void quote_returnsTheDiscountCaseInsensitively() throws Exception {
        Coupon coupon = flat50();

        mvc.perform(get("/internal/coupons/save50/quote")
                        .param("orderValue", "200.00")
                        .param("userId", USER.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponId").value(coupon.getId().toString()))
                .andExpect(jsonPath("$.code").value("SAVE50"))
                .andExpect(jsonPath("$.discountAmount").value(50.00));
    }

    @Test
    void quote_appliesThePercentageCap() throws Exception {
        service.createCoupon(new CreateCouponCommand("PCT20", DiscountType.PERCENTAGE,
                new BigDecimal("20"), BigDecimal.ZERO, new BigDecimal("30"),
                TODAY.minusDays(1), TODAY.plusDays(30), 5, 100));

        // 20% of 400 is 80, capped at 30.
        mvc.perform(get("/internal/coupons/PCT20/quote").param("orderValue", "400"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountAmount").value(30.00));
    }

    @Test
    void quote_isReadOnly() throws Exception {
        Coupon coupon = flat50();

        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/internal/coupons/SAVE50/quote")
                            .param("orderValue", "200")
                            .param("userId", USER.toString()))
                    .andExpect(status().isOk());
        }

        assertThat(coupons.findById(coupon.getId()).orElseThrow().getTotalUsed()).isZero();
        assertThat(service.currentUserUsage(coupon.getId(), USER)).isZero();
    }

    @Test
    void quote_forAUserAtTheirLimit_is422PerUserLimitReached() throws Exception {
        flat50();
        service.redeem("SAVE50", USER);

        mvc.perform(get("/internal/coupons/SAVE50/quote")
                        .param("orderValue", "200")
                        .param("userId", USER.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("PER_USER_LIMIT_REACHED"));
    }

    @Test
    void quote_withoutAUser_skipsOnlyThePerUserLimit() throws Exception {
        flat50();
        service.redeem("SAVE50", USER);

        mvc.perform(get("/internal/coupons/SAVE50/quote").param("orderValue", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountAmount").value(50.00));
    }

    @Test
    void quote_belowTheMinimumOrder_is422WithTheConstraintCode() throws Exception {
        flat50();

        mvc.perform(get("/internal/coupons/SAVE50/quote").param("orderValue", "40"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("MIN_ORDER_VALUE_NOT_MET"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void quote_forAnUnknownCode_is404CouponNotFound() throws Exception {
        mvc.perform(get("/internal/coupons/NOPE1/quote").param("orderValue", "200"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COUPON_NOT_FOUND"));
    }

    @Test
    void quote_withANegativeOrderValue_is400() throws Exception {
        flat50();

        mvc.perform(get("/internal/coupons/SAVE50/quote").param("orderValue", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void quote_withoutAnOrderValue_is400() throws Exception {
        mvc.perform(get("/internal/coupons/SAVE50/quote"))
                .andExpect(status().isBadRequest());
    }
}
