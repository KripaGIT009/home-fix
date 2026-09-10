package com.homefix.reporting.report;

import com.homefix.reporting.domain.GenerationMode;
import com.homefix.reporting.domain.Report;

/**
 * The outcome of a report request.
 *
 * <ul>
 *   <li>Synchronous ({@code &le; 7 days}): {@link #report()} is the materialised report and
 *       {@link #accepted()} is {@code false} (Requirement 20.2).</li>
 *   <li>Asynchronous ({@code &gt; 7 days}): {@link #report()} is {@code null}, {@link #accepted()}
 *       is {@code true}, and the requestor is emailed a download link when generation completes
 *       (Requirement 20.3).</li>
 * </ul>
 */
public record ReportResult(GenerationMode mode, Report report, boolean accepted) {

    public static ReportResult synchronous(Report report) {
        return new ReportResult(GenerationMode.SYNCHRONOUS, report, false);
    }

    public static ReportResult acceptedForAsync() {
        return new ReportResult(GenerationMode.ASYNCHRONOUS, null, true);
    }
}
