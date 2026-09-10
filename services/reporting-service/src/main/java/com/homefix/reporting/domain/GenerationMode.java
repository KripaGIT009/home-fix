package com.homefix.reporting.domain;

/**
 * How a report request is fulfilled.
 *
 * <ul>
 *   <li>{@link #SYNCHRONOUS} — the range is 7 days or fewer; the report is computed inline and
 *       returned in the response (Requirement 20.2).</li>
 *   <li>{@link #ASYNCHRONOUS} — the range exceeds 7 days; the report is generated in the
 *       background and the requestor is emailed an expiring download link when ready
 *       (Requirement 20.3).</li>
 * </ul>
 */
public enum GenerationMode {
    SYNCHRONOUS,
    ASYNCHRONOUS
}
