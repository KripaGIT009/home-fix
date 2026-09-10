package com.homefix.reporting.export;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.homefix.reporting.domain.ExportFormat;
import com.homefix.reporting.domain.Report;

/**
 * Selects the appropriate {@link ReportExporter} for a requested {@link ExportFormat} and renders
 * the report (Requirement 20.4). Exporters register themselves by format at construction time.
 */
@Component
public class ReportExportService {

    private final Map<ExportFormat, ReportExporter> exporters = new EnumMap<>(ExportFormat.class);

    public ReportExportService(List<ReportExporter> exporterBeans) {
        for (ReportExporter exporter : exporterBeans) {
            exporters.put(exporter.format(), exporter);
        }
    }

    /** Renders the report to a byte payload in the requested format. */
    public byte[] export(Report report, ExportFormat format) {
        return exporterFor(format).export(report);
    }

    /** The MIME content type produced for the requested format. */
    public String contentType(ExportFormat format) {
        return exporterFor(format).contentType();
    }

    private ReportExporter exporterFor(ExportFormat format) {
        ReportExporter exporter = exporters.get(format);
        if (exporter == null) {
            throw new IllegalArgumentException("Unsupported export format: " + format);
        }
        return exporter;
    }
}
