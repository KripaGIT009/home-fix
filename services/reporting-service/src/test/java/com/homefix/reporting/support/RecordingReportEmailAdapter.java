package com.homefix.reporting.support;

import java.util.ArrayList;
import java.util.List;

import com.homefix.reporting.delivery.DownloadLink;
import com.homefix.reporting.delivery.ReportEmailPort;

/**
 * Test double for {@link ReportEmailPort} that records each "report ready" notification so tests
 * can assert the asynchronous email was sent with the expiring link (Requirement 20.3).
 */
public class RecordingReportEmailAdapter implements ReportEmailPort {

    public record Sent(String recipient, DownloadLink link) {
    }

    private final List<Sent> sent = new ArrayList<>();

    @Override
    public void sendReportReady(String recipientEmail, DownloadLink link) {
        sent.add(new Sent(recipientEmail, link));
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }
}
