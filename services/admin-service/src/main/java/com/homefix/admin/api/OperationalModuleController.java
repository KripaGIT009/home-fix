package com.homefix.admin.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.admin.module.OperationalModuleService;
import com.homefix.admin.rbac.AdminModule;

/**
 * REST surface for the operational modules whose actions are uniform create/update/delete/approve/
 * reject operations coordinated by the Admin Service (Requirement 19.2). Each module maps to a
 * distinct base path and {@link AdminModule}; every handler enforces module RBAC and records an
 * Audit_Log entry via {@link OperationalModuleService} (Requirement 19.6–19.8).
 *
 * <p>The following modules are covered here:
 * User Management, Provider Management, Service Category Management, Pricing Configuration,
 * Booking Management, Payment and Refund Management, Complaint Management, Review Moderation,
 * Coupon Management, Notification Templates, Report Generation.
 *
 * <p>Verification Queue, Dispatch Rule Configuration, Audit Logs, and System Configuration have
 * dedicated controllers because their contracts differ (read-only queue, weight validation,
 * read-only log, SUPER_ADMIN-only respectively).
 */
@RestController
public class OperationalModuleController {

    private final OperationalModuleService service;

    public OperationalModuleController(OperationalModuleService service) {
        this.service = service;
    }

    // ---------- User Management ----------

    @PutMapping("/admin/users/{id}")
    public ResponseEntity<Map<String, Object>> updateUser(@PathVariable("id") String id,
                                                           @RequestBody Map<String, Object> body,
                                                           Authentication auth) {
        return ResponseEntity.ok(service.update(auth, AdminModule.USER_MANAGEMENT, "USER", id, body));
    }

    @DeleteMapping("/admin/users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable("id") String id, Authentication auth) {
        service.delete(auth, AdminModule.USER_MANAGEMENT, "USER", id);
        return ResponseEntity.noContent().build();
    }

    // ---------- Provider Management ----------

    @PutMapping("/admin/providers/{id}")
    public ResponseEntity<Map<String, Object>> updateProvider(@PathVariable("id") String id,
                                                              @RequestBody Map<String, Object> body,
                                                              Authentication auth) {
        return ResponseEntity.ok(
                service.update(auth, AdminModule.PROVIDER_MANAGEMENT, "PROVIDER", id, body));
    }

    @PostMapping("/admin/providers/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveProvider(@PathVariable("id") String id,
                                                              @RequestBody(required = false) Map<String, Object> body,
                                                              Authentication auth) {
        return ResponseEntity.ok(
                service.approve(auth, AdminModule.PROVIDER_MANAGEMENT, "PROVIDER", id, body));
    }

    @PostMapping("/admin/providers/{id}/reject")
    public ResponseEntity<Map<String, Object>> rejectProvider(@PathVariable("id") String id,
                                                             @RequestBody(required = false) Map<String, Object> body,
                                                             Authentication auth) {
        return ResponseEntity.ok(
                service.reject(auth, AdminModule.PROVIDER_MANAGEMENT, "PROVIDER", id, body));
    }

    // ---------- Service Category Management ----------

    @PostMapping("/admin/categories")
    public ResponseEntity<Map<String, Object>> createCategory(@RequestBody Map<String, Object> body,
                                                              Authentication auth) {
        String id = String.valueOf(body.get("id"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(auth, AdminModule.SERVICE_CATEGORY_MANAGEMENT, "SERVICE_CATEGORY", id, body));
    }

    @PutMapping("/admin/categories/{id}")
    public ResponseEntity<Map<String, Object>> updateCategory(@PathVariable("id") String id,
                                                             @RequestBody Map<String, Object> body,
                                                             Authentication auth) {
        return ResponseEntity.ok(
                service.update(auth, AdminModule.SERVICE_CATEGORY_MANAGEMENT, "SERVICE_CATEGORY", id, body));
    }

    @DeleteMapping("/admin/categories/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable("id") String id, Authentication auth) {
        service.delete(auth, AdminModule.SERVICE_CATEGORY_MANAGEMENT, "SERVICE_CATEGORY", id);
        return ResponseEntity.noContent().build();
    }

    // ---------- Pricing Configuration ----------

    @PutMapping("/admin/pricing/{id}")
    public ResponseEntity<Map<String, Object>> updatePricing(@PathVariable("id") String id,
                                                             @RequestBody Map<String, Object> body,
                                                             Authentication auth) {
        return ResponseEntity.ok(
                service.update(auth, AdminModule.PRICING_CONFIGURATION, "PRICING_CONFIG", id, body));
    }

    // ---------- Booking Management ----------

    @PutMapping("/admin/bookings/{id}")
    public ResponseEntity<Map<String, Object>> updateBooking(@PathVariable("id") String id,
                                                             @RequestBody Map<String, Object> body,
                                                             Authentication auth) {
        return ResponseEntity.ok(
                service.update(auth, AdminModule.BOOKING_MANAGEMENT, "BOOKING", id, body));
    }

    // ---------- Payment and Refund Management ----------

    @PostMapping("/admin/payments/{id}/refund")
    public ResponseEntity<Map<String, Object>> refundPayment(@PathVariable("id") String id,
                                                             @RequestBody(required = false) Map<String, Object> body,
                                                             Authentication auth) {
        return ResponseEntity.ok(
                service.approve(auth, AdminModule.PAYMENT_AND_REFUND_MANAGEMENT, "PAYMENT", id, body));
    }

    // ---------- Complaint Management ----------

    @PutMapping("/admin/complaints/{id}")
    public ResponseEntity<Map<String, Object>> updateComplaint(@PathVariable("id") String id,
                                                              @RequestBody Map<String, Object> body,
                                                              Authentication auth) {
        return ResponseEntity.ok(
                service.update(auth, AdminModule.COMPLAINT_MANAGEMENT, "COMPLAINT", id, body));
    }

    // ---------- Review Moderation ----------

    @DeleteMapping("/admin/reviews/{id}")
    public ResponseEntity<Void> removeReview(@PathVariable("id") String id, Authentication auth) {
        service.delete(auth, AdminModule.REVIEW_MODERATION, "REVIEW", id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/reviews/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveReview(@PathVariable("id") String id,
                                                            @RequestBody(required = false) Map<String, Object> body,
                                                            Authentication auth) {
        return ResponseEntity.ok(
                service.approve(auth, AdminModule.REVIEW_MODERATION, "REVIEW", id, body));
    }

    // ---------- Coupon Management ----------

    @PostMapping("/admin/coupons")
    public ResponseEntity<Map<String, Object>> createCoupon(@RequestBody Map<String, Object> body,
                                                            Authentication auth) {
        String id = String.valueOf(body.get("code"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(auth, AdminModule.COUPON_MANAGEMENT, "COUPON", id, body));
    }

    @DeleteMapping("/admin/coupons/{id}")
    public ResponseEntity<Void> deleteCoupon(@PathVariable("id") String id, Authentication auth) {
        service.delete(auth, AdminModule.COUPON_MANAGEMENT, "COUPON", id);
        return ResponseEntity.noContent().build();
    }

    // ---------- Notification Templates ----------

    @PutMapping("/admin/notification-templates/{id}")
    public ResponseEntity<Map<String, Object>> updateTemplate(@PathVariable("id") String id,
                                                             @RequestBody Map<String, Object> body,
                                                             Authentication auth) {
        return ResponseEntity.ok(
                service.update(auth, AdminModule.NOTIFICATION_TEMPLATES, "NOTIFICATION_TEMPLATE", id, body));
    }

    // ---------- Report Generation ----------

    @PostMapping("/admin/reports")
    public ResponseEntity<Map<String, Object>> generateReport(@RequestBody Map<String, Object> body,
                                                              Authentication auth) {
        String id = String.valueOf(body.get("reportType"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(auth, AdminModule.REPORT_GENERATION, "REPORT", id, body));
    }
}
