package com.homefix.promotion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.homefix.promotion.domain.Coupon;
import com.homefix.promotion.domain.DiscountType;
import com.homefix.promotion.service.CouponException;
import com.homefix.promotion.service.CouponException.ConstraintCode;
import com.homefix.promotion.service.CouponService;
import com.homefix.promotion.service.CouponValidationResult;
import com.homefix.promotion.service.CreateCouponCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web-layer tests for {@link CouponController}. The no-path-variable endpoints (create, validate,
 * redeem, cancel) run through standalone MockMvc so JSON (de)serialization, the shared error
 * envelope, and status mapping are exercised end-to-end (Requirement 21.2, 21.3, 21.5); the
 * path-variable endpoints (getById, deactivate, activate) are exercised by direct calls because
 * the build does not enable the {@code -parameters} flag.
 */
class CouponControllerTest {

    private CouponService service;
    private CouponController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CouponService.class);
        controller = new CouponController(service);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Coupon coupon(String code) {
        return Coupon.create(code, DiscountType.FLAT, new BigDecimal("50"), new BigDecimal("100"),
                null, LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), 2, 3);
    }

    @Test
    void create_returns201() throws Exception {
        when(service.createCoupon(any(CreateCouponCommand.class))).thenReturn(coupon("SAVE50"));

        String body = """
                {"code":"SAVE50","discountType":"FLAT","discountValue":50,"minOrderValue":100,
                 "validFrom":"2024-01-01","expiryDate":"2024-12-31","perUserLimit":2,"totalLimit":3}
                """;

        mvc.perform(post("/coupons").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SAVE50"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void create_invalidCodePattern_returns400() throws Exception {
        String body = """
                {"code":"no","discountType":"FLAT","discountValue":50,"minOrderValue":100,
                 "validFrom":"2024-01-01","expiryDate":"2024-12-31","perUserLimit":2,"totalLimit":3}
                """;

        mvc.perform(post("/coupons").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void validate_returnsDiscount() throws Exception {
        UUID user = UUID.randomUUID();
        when(service.validate(eq("SAVE50"), eq(user), eq(new BigDecimal("200"))))
                .thenReturn(new CouponValidationResult(UUID.randomUUID(), "SAVE50", new BigDecimal("50.00")));

        String body = """
                {"code":"SAVE50","userId":"%s","orderValue":200}
                """.formatted(user);

        mvc.perform(post("/coupons/validate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SAVE50"))
                .andExpect(jsonPath("$.discountAmount").value(50.00));
    }

    @Test
    void validate_constraintViolation_maps422() throws Exception {
        UUID user = UUID.randomUUID();
        when(service.validate(any(), any(), any()))
                .thenThrow(CouponException.constraintViolated(ConstraintCode.MIN_ORDER_VALUE_NOT_MET,
                        "order value below the minimum"));

        String body = """
                {"code":"SAVE50","userId":"%s","orderValue":10}
                """.formatted(user);

        mvc.perform(post("/coupons/validate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("MIN_ORDER_VALUE_NOT_MET"));
    }

    @Test
    void redeem_returnsUpdatedCoupon() throws Exception {
        UUID user = UUID.randomUUID();
        when(service.redeem(eq("SAVE50"), eq(user))).thenReturn(coupon("SAVE50"));

        String body = """
                {"code":"SAVE50","userId":"%s"}
                """.formatted(user);

        mvc.perform(post("/coupons/redeem").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SAVE50"));
    }

    @Test
    void cancel_returnsUpdatedCoupon() throws Exception {
        UUID user = UUID.randomUUID();
        when(service.cancelRedemption(eq("SAVE50"), eq(user))).thenReturn(coupon("SAVE50"));

        String body = """
                {"code":"SAVE50","userId":"%s"}
                """.formatted(user);

        mvc.perform(post("/coupons/cancel").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void pathVariableEndpoints_delegate() {
        UUID id = UUID.randomUUID();
        when(service.getCoupon(id)).thenReturn(coupon("SAVE50"));
        when(service.getCouponByCode("SAVE50")).thenReturn(coupon("SAVE50"));
        when(service.deactivateCoupon(id)).thenReturn(coupon("SAVE50"));
        when(service.activateCoupon(id)).thenReturn(coupon("SAVE50"));

        assertThat(controller.getById(id).code()).isEqualTo("SAVE50");
        assertThat(controller.getByCode("SAVE50").code()).isEqualTo("SAVE50");
        controller.deactivate(id);
        controller.activate(id);

        verify(service).deactivateCoupon(id);
        verify(service).activateCoupon(id);
    }
}
