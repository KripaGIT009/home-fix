package com.homefix.reporting.api;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.reporting.api.dto.AdminReportRequestDto;
import com.homefix.reporting.api.dto.AdminReportResultDto;
import com.homefix.reporting.api.dto.AdminReportTypeDto;
import com.homefix.reporting.api.dto.ReportResponseDto;
import com.homefix.reporting.domain.ReportType;
import com.homefix.reporting.rbac.ReportAuthorization;

import jakarta.validation.Valid;

/**
 * Admin Portal surface for report generation (Requirements 19.2, 20).
 *
 * <p>This is an adapter over {@link ReportController}, not a second implementation: each request
 * is translated into the existing {@code ReportRequestDto} and handed to the same handler, so the
 * Finance_Admin restriction (Requirement 20.6), the 7-day synchronous/asynchronous split
 * (Requirements 20.2, 20.3) and the export rendering stay in one place. Only the portal's field
 * names and its {@code SYNC}/{@code QUEUED} result shape live here.
 *
 * <p>The coarse role gate (the reporting tier: ADMIN, SUPER_ADMIN, FINANCE_ADMIN) is applied by
 * the shared {@code RbacEnforcementFilter} ({@code ReportingRbacConfig}).
 */
@RestController
@RequestMapping("/admin/reports")
public class AdminReportController {

    private final ReportController reports;
    private final ReportAuthorization authorization;

    public AdminReportController(ReportController reports, ReportAuthorization authorization) {
        this.reports = reports;
        this.authorization = authorization;
    }

    /**
     * The report types the caller may run. Finance-restricted types are listed only for a
     * Finance_Admin, so the picker never offers a report that would come back as a 403; the
     * {@code financeOnly} flag still marks them for the Finance_Admin who sees them.
     */
    @GetMapping("/types")
    public List<AdminReportTypeDto> types(Authentication authentication) {
        Set<String> roles = authorization.extractRoles(authentication);
        return Arrays.stream(ReportType.values())
                .filter(type -> authorization.canAccess(roles, type))
                .map(AdminReportTypeDto::from)
                .toList();
    }

    /**
     * Requests a report: {@code SYNC} (200) when generated inline, {@code QUEUED} (202) when
     * handed to the background path that emails a download link.
     */
    @PostMapping
    public ResponseEntity<AdminReportResultDto> request(@Valid @RequestBody AdminReportRequestDto dto,
                                                        Authentication authentication) {
        ResponseEntity<ReportResponseDto> response =
                reports.generate(dto.toReportRequest(), authentication);
        return ResponseEntity.status(response.getStatusCode())
                .body(AdminReportResultDto.from(response.getBody()));
    }

    /**
     * Downloads a synchronous report as a PDF or CSV attachment (Requirement 20.4), for the portal
     * to fetch with its bearer token; a long range returns 202 with no body, exactly like
     * {@code POST /reports/export}.
     */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@Valid @RequestBody AdminReportRequestDto dto,
                                         Authentication authentication) {
        return reports.export(dto.toReportRequest(), authentication);
    }
}
