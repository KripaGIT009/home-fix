package com.homefix.promotion.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.homefix.promotion.domain.Coupon;
import com.homefix.promotion.domain.CouponStatus;
import com.homefix.promotion.domain.DiscountType;
import com.homefix.promotion.service.CouponException;
import com.homefix.promotion.service.CouponService;
import com.homefix.promotion.service.CreateCouponCommand;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web-layer tests for {@link AdminCouponController}: the JSON shape the Admin Portal's
 * {@code Coupon} type reads (totalRedeemed, derived status, ISO dates), that create and deactivate
 * delegate to the existing {@link CouponService} paths, and the error envelope. Role enforcement is
 * covered by {@code PromotionRbacConfigTest}.
 */
class AdminCouponControllerTest {

    private static final LocalDate FROM = LocalDate.of(2024, 6, 1);
    private static final LocalDate TO = LocalDate.of(2024, 6, 30);

    private CouponService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CouponService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminCouponController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                // Boot's auto-configured mapper writes dates as ISO-8601; mirror it here.
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    private static Coupon percentage(String code) {
        return Coupon.create(code, DiscountType.PERCENTAGE, new BigDecimal("10"),
                new BigDecimal("200"), new BigDecimal("150"), FROM, TO, 1, 500);
    }

    private static Coupon flat(String code) {
        return Coupon.create(code, DiscountType.FLAT, new BigDecimal("50"), BigDecimal.ZERO, null,
                FROM, TO, 2, 100);
    }

    @Test
    void listReturnsThePortalShapeAsABareArray() throws Exception {
        Coupon pct = percentage("TENOFF");
        pct.incrementTotalUsed();
        Coupon flat = flat("FLAT50");
        when(service.listForAdmin()).thenReturn(List.of(pct, flat));
        when(service.statusOf(pct)).thenReturn(CouponStatus.ACTIVE);
        when(service.statusOf(flat)).thenReturn(CouponStatus.EXPIRED);

        mvc.perform(get("/admin/coupons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(pct.getId().toString()))
                .andExpect(jsonPath("$[0].code").value("TENOFF"))
                .andExpect(jsonPath("$[0].discountType").value("PERCENTAGE"))
                .andExpect(jsonPath("$[0].discountValue").value(10))
                .andExpect(jsonPath("$[0].minOrderValue").value(200))
                .andExpect(jsonPath("$[0].maxDiscountCap").value(150))
                .andExpect(jsonPath("$[0].validFrom").value("2024-06-01"))
                .andExpect(jsonPath("$[0].expiryDate").value("2024-06-30"))
                .andExpect(jsonPath("$[0].perUserLimit").value(1))
                .andExpect(jsonPath("$[0].totalLimit").value(500))
                .andExpect(jsonPath("$[0].totalRedeemed").value(1))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].totalUsed").doesNotExist())
                .andExpect(jsonPath("$[0].active").doesNotExist())
                .andExpect(jsonPath("$[1].maxDiscountCap").doesNotExist())
                .andExpect(jsonPath("$[1].status").value("EXPIRED"));
    }

    @Test
    void createDelegatesToTheExistingCreatePathAndReturns201() throws Exception {
        Coupon created = percentage("WELCOME10");
        when(service.createCoupon(any(CreateCouponCommand.class))).thenReturn(created);
        when(service.statusOf(created)).thenReturn(CouponStatus.ACTIVE);

        // Exactly the portal's CreateCouponPayload.
        String body = """
                {"code":"welcome10","discountType":"PERCENTAGE","discountValue":10,
                 "minOrderValue":200,"maxDiscountCap":150,"validFrom":"2024-06-01",
                 "expiryDate":"2024-06-30","perUserLimit":1,"totalLimit":500}
                """;
        mvc.perform(post("/admin/coupons").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("WELCOME10"))
                .andExpect(jsonPath("$.totalRedeemed").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        ArgumentCaptor<CreateCouponCommand> captor = ArgumentCaptor.forClass(CreateCouponCommand.class);
        verify(service).createCoupon(captor.capture());
        assertThat(captor.getValue().validFrom()).isEqualTo(FROM);
        assertThat(captor.getValue().maxDiscountCap()).isEqualByComparingTo("150");
    }

    @Test
    void createWithAnInvalidCodeIsRejectedBeforeTheService() throws Exception {
        String body = """
                {"code":"no","discountType":"FLAT","discountValue":50,"minOrderValue":0,
                 "validFrom":"2024-06-01","expiryDate":"2024-06-30","perUserLimit":1,"totalLimit":1}
                """;
        mvc.perform(post("/admin/coupons").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verify(service, never()).createCoupon(any());
    }

    @Test
    void createWithADuplicateCodeSurfacesAs409() throws Exception {
        when(service.createCoupon(any(CreateCouponCommand.class)))
                .thenThrow(CouponException.duplicateCode("exists"));
        String body = """
                {"code":"FLAT50","discountType":"FLAT","discountValue":50,"minOrderValue":0,
                 "validFrom":"2024-06-01","expiryDate":"2024-06-30","perUserLimit":1,"totalLimit":1}
                """;

        mvc.perform(post("/admin/coupons").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void deactivateDelegatesAndReturnsTheInactiveCoupon() throws Exception {
        Coupon coupon = flat("FLAT50");
        coupon.deactivate();
        when(service.deactivateCoupon(coupon.getId())).thenReturn(coupon);
        when(service.statusOf(coupon)).thenReturn(CouponStatus.INACTIVE);

        mvc.perform(patch("/admin/coupons/{id}/deactivate", coupon.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(coupon.getId().toString()))
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        verify(service).deactivateCoupon(coupon.getId());
    }
}
