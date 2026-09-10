package com.homefix.reporting.delivery;

/**
 * Hexagonal port over outbound email used to notify the requestor when an asynchronous report is
 * ready for download (Requirement 20.3).
 *
 * <p>The service depends only on this interface; a production adapter dispatches via the shared
 * notification pipeline or an email vendor. The requestor's email address is treated as PII and is
 * never logged (Requirement 26.4).
 */
public interface ReportEmailPort {

    /**
     * Sends the "report ready" notification carrying the expiring download link.
     *
     * @param recipientEmail requestor email address (PII — never logged)
     * @param link           the expiring download link
     */
    void sendReportReady(String recipientEmail, DownloadLink link);
}
