package com.homefix.promotion.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.promotion.api.dto.CouponResponse;
import com.homefix.promotion.api.dto.CreateCouponRequest;
import com.homefix.promotion.api.dto.RedeemCouponRequest;
import com.homefix.promotion.api.dto.ValidateCouponRequest;
import com.homefix.promotion.service.CouponService;
import com.homefix.promotion.service.CouponValidationResult;

import jakarta.validation.Valid;

/**
 * REST surface for the Promotion / Coupon Service (Requirement 21).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>Admin coupon CRUD — create, read, deactivate/activate (Requirement 21.1, 21.4).</li>
 *   <li>Checkout-time validation returning the applicable discount (Requirement 21.2).</li>
 *   <li>Atomic redemption incrementing the total and per-user counters (Requirement 21.3,
 *       Property 20).</li>
 *   <li>Cancellation decrement that restores the counters before payment capture (Requirement
 *       21.5, Property 21).</li>
 * </ul>
 *
 * <p>Authentication is enforced by the shared security filter chain (see {@code WebSecurityConfig});
 * fine-grained Admin vs. Customer role checks are performed by the shared
 * {@code RbacEnforcementFilter} (Task 4). Per Requirement 26.4, no customer PII is logged here.
 */
@RestController
@RequestMapping("/coupons")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    // ===================== Admin CRUD (Requirement 21.1, 21.4) =====================

    /** Admin: create a coupon from validated attributes (Requirement 21.1). */
    @PostMapping
    public ResponseEntity<CouponResponse> create(@Valid @RequestBody CreateCouponRequest req) {
        CouponResponse body = CouponResponse.from(couponService.createCoupon(req.toCommand()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** Read a coupon by its identifier. */
    @GetMapping("/{id}")
    public CouponResponse getById(@PathVariable UUID id) {
        return CouponResponse.from(couponService.getCoupon(id));
    }

    /** Read a coupon by its (case-insensitive) code. */
    @GetMapping("/code/{code}")
    public CouponResponse getByCode(@PathVariable String code) {
        return CouponResponse.from(couponService.getCouponByCode(code));
    }

    /** Admin: immediately prevent further redemptions of the coupon (Requirement 21.4). */
    @PostMapping("/{id}/deactivate")
    public CouponResponse deactivate(@PathVariable UUID id) {
        return CouponResponse.from(couponService.deactivateCoupon(id));
    }

    /** Admin: re-enable a previously deactivated coupon. */
    @PostMapping("/{id}/activate")
    public CouponResponse activate(@PathVariable UUID id) {
        return CouponResponse.from(couponService.activateCoupon(id));
    }

    // ===================== Validation (Requirement 21.2) =====================

    /**
     * Validate a coupon at checkout, returning the applicable discount for the supplied order value
     * (Requirement 21.2). A violated constraint surfaces as a 422 with a constraint-specific code.
     */
    @PostMapping("/validate")
    public CouponValidationResult validate(@Valid @RequestBody ValidateCouponRequest req) {
        return couponService.validate(req.code(), req.userId(), req.orderValue());
    }

    // ===================== Redemption / cancellation (Requirement 21.3, 21.5) =====================

    /**
     * Redeem a coupon for a user, atomically incrementing the total and per-user usage counters
     * (Requirement 21.3, Property 20).
     */
    @PostMapping("/redeem")
    public CouponResponse redeem(@Valid @RequestBody RedeemCouponRequest req) {
        return CouponResponse.from(couponService.redeem(req.code(), req.userId()));
    }

    /**
     * Cancel a prior redemption (booking cancelled before payment capture), atomically decrementing
     * the counters to restore availability (Requirement 21.5, Property 21).
     */
    @PostMapping("/cancel")
    public CouponResponse cancel(@Valid @RequestBody RedeemCouponRequest req) {
        return CouponResponse.from(couponService.cancelRedemption(req.code(), req.userId()));
    }
}
