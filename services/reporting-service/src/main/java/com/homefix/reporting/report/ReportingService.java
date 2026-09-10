package com.homefix.reporting.report;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.homefix.reporting.config.ReportingProperties;
import com.homefix.reporting.delivery.DownloadLink;
import com.homefix.reporting.delivery.DownloadLinkStorePort;
import com.homefix.reporting.delivery.ReportEmailPort;
import com.homefix.reporting.domain.GenerationMode;
import com.homefix.reporting.domain.Report;
import com.homefix.reporting.domain.ReportFilters;

/**
 * Coordinates report generation, routing each request to the synchronous or asynchronous path at
 * the 7-day threshold (Requirement 20.2, 20.3).
 *
 * <ul>
 *   <li>Synchronous ({@code &le; 7 days}): the report is generated inline and returned. An empty
 *       result carries the "no data" message (Requirement 20.2).</li>
 *   <li>Asynchronous ({@code &gt; 7 days}): the request is accepted immediately; generation, export,
 *       artifact storage, and the "report ready" email happen on a background executor. The
 *       download link expires after 7 days (Requirement 20.3).</li>
 * </ul>
 *
 * <p>Role authorization for restricted reports is enforced upstream in the controller
 * ({@code ReportAuthorization}); this service assumes the request is permitted.
 */
@Service
public class ReportingService {

    private static final Logger log = LoggerFactory.getLogger(ReportingService.class);

    private final ReportGenerator generator;
    private final com.homefix.reporting.export.ReportExportService exportService;
    private final DownloadLinkStorePort downloadLinkStore;
    private final ReportEmailPort emailPort;
    private final ReportingProperties properties;
    private final Executor asyncExecutor;

    public ReportingService(ReportGenerator generator,
                            com.homefix.reporting.export.ReportExportService exportService,
                            DownloadLinkStorePort downloadLinkStore,
                            ReportEmailPort emailPort,
                            ReportingProperties properties,
                            Executor reportGenerationExecutor) {
        this.generator = generator;
        this.exportService = exportService;
        this.downloadLinkStore = downloadLinkStore;
        this.emailPort = emailPort;
        this.properties = properties;
        this.asyncExecutor = reportGenerationExecutor;
    }

    /**
     * Handles a report request, returning synchronously for short ranges or accepting the request
     * for background processing for long ranges.
     */
    public ReportResult submit(ReportRequest request) {
        GenerationMode mode = selectMode(request.filters());
        if (mode == GenerationMode.SYNCHRONOUS) {
            Report report = generator.generate(request.type(), request.filters());
            return ReportResult.synchronous(report);
        }
        asyncExecutor.execute(() -> generateAndDeliver(request));
        return ReportResult.acceptedForAsync();
    }

    /**
     * Chooses the generation mode using the configurable threshold (default 7 inclusive days),
     * mirroring {@link com.homefix.reporting.domain.GenerationModeSelector}.
     */
    GenerationMode selectMode(ReportFilters filters) {
        return filters.inclusiveDayCount() <= properties.getSynchronousThresholdDays()
                ? GenerationMode.SYNCHRONOUS
                : GenerationMode.ASYNCHRONOUS;
    }

    /**
     * Background work for the asynchronous path: generate, render, store, and email the requestor.
     * Package-private so it can be exercised directly in unit tests without an executor.
     */
    void generateAndDeliver(ReportRequest request) {
        try {
            Report report = generator.generate(request.type(), request.filters());
            byte[] payload = exportService.export(report, request.format());
            DownloadLink link = downloadLinkStore.store(request.type(), request.format(), payload);
            emailPort.sendReportReady(request.requestorEmail(), link);
        } catch (RuntimeException ex) {
            // Never log the requestor email or filter values (PII, Requirement 26.4).
            log.error("Asynchronous generation failed for report type {}", request.type(), ex);
        }
    }
}
