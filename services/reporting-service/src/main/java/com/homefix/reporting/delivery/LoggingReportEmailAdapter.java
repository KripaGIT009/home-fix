package com.homefix.reporting.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link ReportEmailPort} adapter used in local/dev/test profiles.
 *
 * <p>It does not contact an email provider — it records that a "report ready" message would be
 * sent. Neither the recipient address nor the download URL is logged (Requirement 26.4); the
 * signed URL is a bearer capability and the address is PII. Real deployments supply a concrete
 * adapter over the shared notification pipeline.
 *
 * <p>Activated when no other {@link ReportEmailPort} bean is present.
 */
@Component
public class LoggingReportEmailAdapter implements ReportEmailPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingReportEmailAdapter.class);

    @Override
    public void sendReportReady(String recipientEmail, DownloadLink link) {
        // Do not log the recipient address (PII) or the signed download URL (bearer capability).
        log.info("Report-ready email dispatch accepted by logging adapter");
    }
}
