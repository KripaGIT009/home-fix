package com.homefix.promotion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.homefix.promotion.domain.Coupon;
import com.homefix.promotion.domain.DiscountType;
import com.homefix.promotion.service.CouponException;
import com.homefix.promotion.service.CouponException.ConstraintCode;
import com.homefix.promotion.service.CouponService;
import com.homefix.promotion.service.CouponValidationResult;
import com.homefix.promotion.service.CreateCouponCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web-layer tests for {@link CouponController}. The no-path-variable endpoints (create, validate,
 * redeem, cancel) run through standalone MockMvc so JSON (de)serialization, the shared error
 * envelope, and status mapping are exercised end-to-end (Requirement 21.2, 21.3, 21.5); the
 * path-variable endpoints (getById, deactivate, activate) are exercised by direct calls because
 * the build does not enable the {@code -parameters} flag.
 *
 * <p>Validate, redeem and cancel name the customer in the request <em>body</em>, so the controller
 * asserts ownership through {@link CallerIdentity}. These tests therefore populate the
 * {@link SecurityContextHolder} with a caller whose id matches the body {@code userId}, and the
 * {@code ownership_*} tests cover the mismatch and staff-override cases.
 */
class CouponControllerTest {

    private CouponService service;
    private CouponController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CouponService.class);
        controller = new CouponController(service, new CallerIdentity());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Populates the security context with a caller of the given id and roles. */
    private void authenticateAs(UUID callerId, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        callerId.toString(),
                        null,
                        List.of(roles).stream()
                                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                                .toList()));
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
        authenticateAs(user, "CUSTOMER");
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
        authenticateAs(user, "CUSTOMER");
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
        authenticateAs(user, "CUSTOMER");
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
        authenticateAs(user, "CUSTOMER");
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

    // =================== Ownership of the body userId (CallerIdentity) ===================

    @Test
    void ownership_redeem_forAnotherUser_isForbidden() throws Exception {
        UUID callerA = UUID.randomUUID();
        UUID callerB = UUID.randomUUID();
        authenticateAs(callerA, "CUSTOMER");

        String body = """
                {"code":"SAVE50","userId":"%s"}
                """.formatted(callerB);

        mvc.perform(post("/coupons/redeem").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        verify(service, never()).redeem(any(), any());
    }

    @Test
    void ownership_redeem_forSelf_succeeds() throws Exception {
        UUID caller = UUID.randomUUID();
        authenticateAs(caller, "CUSTOMER");
        when(service.redeem("SAVE50", caller)).thenReturn(coupon("SAVE50"));

        String body = """
                {"code":"SAVE50","userId":"%s"}
                """.formatted(caller);

        mvc.perform(post("/coupons/redeem").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        verify(service).redeem("SAVE50", caller);
    }

    @Test
    void ownership_redeem_byStaff_forAnybody_succeeds() throws Exception {
        UUID supportAgent = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        authenticateAs(supportAgent, "SUPPORT_AGENT");
        when(service.redeem("SAVE50", customer)).thenReturn(coupon("SAVE50"));

        String body = """
                {"code":"SAVE50","userId":"%s"}
                """.formatted(customer);

        mvc.perform(post("/coupons/redeem").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        verify(service).redeem("SAVE50", customer);
    }

    @Test
    void ownership_validate_forAnotherUser_isForbidden() throws Exception {
        authenticateAs(UUID.randomUUID(), "CUSTOMER");

        String body = """
                {"code":"SAVE50","userId":"%s","orderValue":200}
                """.formatted(UUID.randomUUID());

        mvc.perform(post("/coupons/validate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        verify(service, never()).validate(any(), any(), any());
    }

    @Test
    void ownership_validate_byAdmin_forAnybody_succeeds() throws Exception {
        UUID customer = UUID.randomUUID();
        authenticateAs(UUID.randomUUID(), "ADMIN");
        when(service.validate(eq("SAVE50"), eq(customer), any()))
                .thenReturn(new CouponValidationResult(UUID.randomUUID(), "SAVE50", new BigDecimal("50.00")));

        String body = """
                {"code":"SAVE50","userId":"%s","orderValue":200}
                """.formatted(customer);

        mvc.perform(post("/coupons/validate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void ownership_cancel_forAnotherUser_isForbidden() throws Exception {
        authenticateAs(UUID.randomUUID(), "CUSTOMER");

        String body = """
                {"code":"SAVE50","userId":"%s"}
                """.formatted(UUID.randomUUID());

        mvc.perform(post("/coupons/cancel").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        verify(service, never()).cancelRedemption(any(), any());
    }

    @Test
    void ownership_cancel_byStaff_forAnybody_succeeds() throws Exception {
        UUID customer = UUID.randomUUID();
        authenticateAs(UUID.randomUUID(), "DISPATCHER");
        when(service.cancelRedemption("SAVE50", customer)).thenReturn(coupon("SAVE50"));

        String body = """
                {"code":"SAVE50","userId":"%s"}
                """.formatted(customer);

        mvc.perform(post("/coupons/cancel").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        verify(service).cancelRedemption("SAVE50", customer);
    }

    @Test
    void ownership_redeem_withNoAuthentication_isUnauthorized() throws Exception {
        String body = """
                {"code":"SAVE50","userId":"%s"}
                """.formatted(UUID.randomUUID());

        mvc.perform(post("/coupons/redeem").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));

        verify(service, never()).redeem(any(), any());
    }
}
