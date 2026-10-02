package com.homefix.promotion.api;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.promotion.service.CouponException;
import com.homefix.promotion.service.CouponService;
import com.homefix.promotion.service.CouponValidationResult;

/**
 * Service-to-service coupon quotes, under {@code /internal}.
 *
 * <p>The Pricing Engine prices a coupon into an estimate (Requirement 6.10). It used to keep its own
 * coupon table, which nothing could populate (CODEBASE_REVIEW 8.3); it now asks this service, the
 * coupon owner, so the estimate carries exactly the discount and constraint codes that
 * {@code POST /coupons/validate} and redemption use. The quote runs the same
 * {@link CouponService#validate} and is read-only: no usage counter moves until the coupon is
 * redeemed.
 *
 * <p>Not reachable by end users: {@code /internal/**} requires the shared service credential in
 * {@code X-Internal-Api-Key} (see {@code InternalApiKeyFilter}) and is not routed by the API
 * Gateway. Because the caller is a service, not the customer, there is no ownership check on
 * {@code userId} here; the Pricing Engine passes the user its own caller asked about.
 */
@RestController
@RequestMapping("/internal/coupons")
public class InternalCouponController {

    private final CouponService couponService;

    public InternalCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    /**
     * {@code GET /internal/coupons/{code}/quote?orderValue=..[&userId=..]} — the discount
     * {@code code} gives on {@code orderValue}.
     *
     * @param code       coupon code, case-insensitive
     * @param orderValue pre-coupon order total; must be 0 or greater
     * @param userId     the customer; optional, and when absent the per-user limit is not evaluated
     *                   (it still applies at redemption)
     * @return 200 {@link CouponValidationResult}; 404 {@code COUPON_NOT_FOUND}; 422 with the
     *         violated constraint's code; 400 {@code VALIDATION_ERROR} for a negative order value
     */
    @GetMapping("/{code}/quote")
    public CouponValidationResult quote(@PathVariable("code") String code,
                                        @RequestParam("orderValue") BigDecimal orderValue,
                                        @RequestParam(value = "userId", required = false) UUID userId) {
        if (orderValue.signum() < 0) {
            throw CouponException.validation("orderValue must be 0 or greater");
        }
        return couponService.validate(code, userId, orderValue);
    }
}
