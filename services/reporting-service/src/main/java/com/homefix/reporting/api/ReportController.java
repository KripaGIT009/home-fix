package com.homefix.reporting.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.reporting.api.dto.ReportRequestDto;
import com.homefix.reporting.api.dto.ReportResponseDto;
import com.homefix.reporting.domain.GenerationMode;
import com.homefix.reporting.domain.Report;
import com.homefix.reporting.domain.ReportFilters;
import com.homefix.reporting.export.ReportExportService;
import com.homefix.reporting.rbac.ReportAuthorization;
import com.homefix.reporting.report.ReportRequest;
import com.homefix.reporting.report.ReportResult;
import com.homefix.reporting.report.ReportingService;

import jakarta.validation.Valid;

/**
 * REST surface for report generation (Requirement 20).
 *
 * <p>Every handler first enforces the Finance_Admin restriction via {@link ReportAuthorization};
 * a non-Finance_Admin requesting Payment Reconciliation or Settlement gets a 403 (Requirement
 * 20.6). Short-range requests return the report inline; long-range requests are accepted for
 * background generation (Requirement 20.2, 20.3). The coarse {@code /reports/**} authentication
 * gate is applied by the shared {@code RbacEnforcementFilter}.
 */
@RestController
@RequestMapping("/reports")
public class ReportController {

    private final ReportingService reportingService;
    private final ReportExportService exportService;
    private final ReportAuthorization authorization;

    public ReportController(ReportingService reportingService,
                            ReportExportService exportService,
                            ReportAuthorization authorization) {
        this.reportingService = reportingService;
        this.exportService = exportService;
        this.authorization = authorization;
    }

    /**
     * Generates a report, returning the data inline for short ranges or a 202-style acceptance for
     * long ranges. Restricted report types require the Finance_Admin role (Requirement 20.6).
     */
    @PostMapping
    public ResponseEntity<ReportResponseDto> generate(@Valid @RequestBody ReportRequestDto dto,
                                                      Authentication authentication) {
        authorization.requireAccess(authentication, dto.reportType());
        ReportResult result = reportingService.submit(toRequest(dto, authentication));
        ReportResponseDto body = ReportResponseDto.from(result);
        if (result.mode() == GenerationMode.ASYNCHRONOUS) {
            return ResponseEntity.accepted().body(body);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Exports a short-range report as a PDF or CSV attachment (Requirement 20.4). Long-range
     * requests are not exported inline; callers use {@link #generate} which triggers the async
     * email path. Restricted report types require the Finance_Admin role (Requirement 20.6).
     */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@Valid @RequestBody ReportRequestDto dto,
                                         Authentication authentication) {
        authorization.requireAccess(authentication, dto.reportType());
        ReportResult result = reportingService.submit(toRequest(dto, authentication));
        if (result.mode() == GenerationMode.ASYNCHRONOUS) {
            // Too large for inline export; accepted for async email delivery.
            return ResponseEntity.accepted().build();
        }
        Report report = result.report();
        byte[] payload = exportService.export(report, dto.format());
        String contentType = exportService.contentType(dto.format());
        String filename = report.type().name().toLowerCase() + "."
                + dto.format().name().toLowerCase();
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(payload);
    }

    private ReportRequest toRequest(ReportRequestDto dto, Authentication authentication) {
        ReportFilters filters = new ReportFilters(
                dto.from(), dto.to(), dto.serviceCategory(), dto.region(), dto.providerId());
        String requestor = ReportingPrincipals.requestorReference(authentication);
        return new ReportRequest(dto.reportType(), filters, dto.format(), requestor);
    }
}
