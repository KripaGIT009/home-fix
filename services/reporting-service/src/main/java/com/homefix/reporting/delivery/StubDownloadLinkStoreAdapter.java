package com.homefix.reporting.delivery;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.homefix.reporting.config.ReportingProperties;
import com.homefix.reporting.domain.ExportFormat;
import com.homefix.reporting.domain.ReportType;

/**
 * Default {@link DownloadLinkStorePort} adapter used in local/dev/test profiles.
 *
 * <p>It does not upload to S3 — it mints an opaque, non-guessable artifact token and returns a
 * link that expires after the configured TTL (7 days by default, Requirement 20.3). The concrete
 * S3 signed-URL adapter is wired in a later integration task. No PII is logged (Requirement 26.4).
 *
 * <p>Activated when no other {@link DownloadLinkStorePort} bean is present.
 */
@Component
public class StubDownloadLinkStoreAdapter implements DownloadLinkStorePort {

    private static final Logger log = LoggerFactory.getLogger(StubDownloadLinkStoreAdapter.class);

    private final ReportingProperties properties;
    private final Clock clock;

    public StubDownloadLinkStoreAdapter(ReportingProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public DownloadLink store(ReportType type, ExportFormat format, byte[] payload) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.getDownloadLinkTtl());
        String token = UUID.randomUUID().toString();
        String url = properties.getDownloadBaseUrl() + "/" + token;
        log.info("Stored report artifact of {} bytes for report type {}",
                payload == null ? 0 : payload.length, type);
        return new DownloadLink(url, issuedAt, expiresAt);
    }
}
