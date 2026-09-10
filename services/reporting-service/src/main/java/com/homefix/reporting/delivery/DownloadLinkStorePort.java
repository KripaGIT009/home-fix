package com.homefix.reporting.delivery;

import com.homefix.reporting.domain.ExportFormat;
import com.homefix.reporting.domain.ReportType;

/**
 * Hexagonal port over the artifact store that persists a generated report and mints a 7-day
 * expiring download link for it (Requirement 20.3).
 *
 * <p>A production adapter uploads the payload to S3 and returns a signed URL with a 7-day expiry;
 * tests and local runs use a stub. The service depends only on this interface.
 */
public interface DownloadLinkStorePort {

    /**
     * Stores the rendered report artifact and returns a download link that expires after 7 days.
     *
     * @param type    the report type (for artifact naming/metadata; non-PII)
     * @param format  the export format of the payload
     * @param payload the rendered report bytes
     * @return the expiring download link
     */
    DownloadLink store(ReportType type, ExportFormat format, byte[] payload);
}
