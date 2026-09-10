package com.homefix.admin.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.admin.dashboard.DashboardMetrics;
import com.homefix.admin.dashboard.DashboardService;

/**
 * Admin dashboard endpoint returning the seven real-time metrics refreshed every 60 seconds
 * (Requirement 19.1). Accessible to any Admin-tier role; the coarse {@code /admin/**} RBAC gate
 * is enforced by the shared {@code RbacEnforcementFilter}.
 */
@RestController
@RequestMapping("/admin/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public ResponseEntity<DashboardMetrics> metrics() {
        return ResponseEntity.ok(dashboardService.currentMetrics());
    }
}
