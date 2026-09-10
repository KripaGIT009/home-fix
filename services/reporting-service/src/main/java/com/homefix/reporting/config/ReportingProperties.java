package com.homefix.reporting.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable Reporting-service settings (Requirement 20).
 *
 * <p>Defaults match the acceptance criteria; values are overridable via configuration so
 * operations can tune behaviour without a code change.
 */
@ConfigurationProperties(prefix = "homefix.reporting")
public class ReportingProperties {

    /**
     * Number of days a report request may span (inclusive of both endpoints) before it is routed
     * to the asynchronous path (Requirement 20.2, 20.3). Defaults to 7.
     */
    private long synchronousThresholdDays = 7L;

    /**
     * Lifetime of an asynchronous report download link before it expires (Requirement 20.3).
     * Defaults to 7 days.
     */
    private Duration downloadLinkTtl = Duration.ofDays(7);

    /**
     * Base URL under which generated report artifacts are exposed for download. The stub adapter
     * appends an opaque artifact token to this value.
     */
    private String downloadBaseUrl = "https://reports.homefix.internal/download";

    public long getSynchronousThresholdDays() {
        return synchronousThresholdDays;
    }

    public void setSynchronousThresholdDays(long synchronousThresholdDays) {
        this.synchronousThresholdDays = synchronousThresholdDays;
    }

    public Duration getDownloadLinkTtl() {
        return downloadLinkTtl;
    }

    public void setDownloadLinkTtl(Duration downloadLinkTtl) {
        this.downloadLinkTtl = downloadLinkTtl;
    }

    public String getDownloadBaseUrl() {
        return downloadBaseUrl;
    }

    public void setDownloadBaseUrl(String downloadBaseUrl) {
        this.downloadBaseUrl = downloadBaseUrl;
    }
}
