package com.homefix.promotion.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.promotion.api.dto.AdminCouponResponse;
import com.homefix.promotion.api.dto.CreateCouponRequest;
import com.homefix.promotion.domain.Coupon;
import com.homefix.promotion.service.CouponService;

import jakarta.validation.Valid;

/**
 * Admin Portal coupon management (Requirement 19.2, Requirement 21.1, 21.4). The API gateway routes
 * {@code /admin/coupons/**} here unchanged; {@code PromotionRbacConfig} restricts it to ADMIN and
 * SUPER_ADMIN.
 *
 * <p>Create and deactivate delegate to the same {@link CouponService} methods as
 * {@code POST /coupons} and {@code POST /coupons/{id}/deactivate}, with the same
 * {@link CreateCouponRequest} validation, so the portal gets identical rules (unique code, cap
 * required for PERCENTAGE, expiry after valid_from) and error codes; only the response is reshaped
 * to the portal's {@code Coupon} type.
 *
 * <p>Parameter names are spelled out because the build does not compile with {@code -parameters}.
 */
@RestController
@RequestMapping("/admin/coupons")
public class AdminCouponController {

    private final CouponService couponService;

    public AdminCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    /** All coupons, newest first; a bounded bare array, not paged. */
    @GetMapping
    public List<AdminCouponResponse> list() {
        return couponService.listForAdmin().stream().map(this::toResponse).toList();
    }

    /** Creates a coupon from validated attributes (Requirement 21.1). */
    @PostMapping
    public ResponseEntity<AdminCouponResponse> create(@Valid @RequestBody CreateCouponRequest req) {
        Coupon coupon = couponService.createCoupon(req.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(coupon));
    }

    /** Immediately prevents further redemptions of the coupon (Requirement 21.4). */
    @PatchMapping("/{id}/deactivate")
    public AdminCouponResponse deactivate(@PathVariable("id") UUID id) {
        return toResponse(couponService.deactivateCoupon(id));
    }

    private AdminCouponResponse toResponse(Coupon coupon) {
        return AdminCouponResponse.from(coupon, couponService.statusOf(coupon));
    }
}
