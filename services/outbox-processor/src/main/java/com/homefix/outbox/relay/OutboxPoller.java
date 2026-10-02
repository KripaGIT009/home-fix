package com.homefix.outbox.relay;

import java.util.List;

import com.homefix.outbox.relay.OutboxRelayService.RelayOutcome;
import com.homefix.shared.outbox.OutboxEventEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically drains the transactional outbox (Requirement 22.4).
 *
 * <p>Each cycle claims up to {@code batch-size} due PENDING rows in insertion order (oldest first,
 * preserving rough production order) through {@link OutboxClaimer}, then makes one publish attempt
 * per row with {@link OutboxRelayService#relay(OutboxEventEntity)}. Rows whose last attempt failed
 * are not due until their persisted next-attempt time, so a failing event costs one attempt per
 * backoff step instead of stalling the loop while it waits.
 *
 * <p>The first publish failure ends the cycle and the rest of the batch is released unattempted.
 * A failure is usually the broker or network rather than the row, so working through the batch
 * would charge every remaining row an attempt (and a publish timeout) for the same outage. A row
 * that fails on its own merits is rescheduled with backoff and so no longer heads the next claim.
 *
 * <p>A row {@linkplain RelayOutcome#SKIPPED skipped} for want of lease ends the cycle the same way,
 * and is released together with the rest. The whole batch was claimed with one lease, so every
 * later row would be skipped too; left claimed, they would all sit idle until the lease ran out.
 *
 * <p>The poll cadence is driven by {@code @Scheduled} using
 * {@code homefix.outbox-processor.poll-interval}.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxClaimer claimer;
    private final OutboxRelayService relayService;

    public OutboxPoller(OutboxClaimer claimer, OutboxRelayService relayService) {
        this.claimer = claimer;
        this.relayService = relayService;
    }

    /**
     * Claims a batch of due rows and relays each. Returns the number of rows published so
     * callers/tests can observe progress.
     */
    public int pollOnce() {
        List<OutboxEventEntity> claimed = claimer.claimBatch();
        if (claimed.isEmpty()) {
            return 0;
        }
        log.debug("Claimed {} due outbox events for relay", claimed.size());
        int published = 0;
        for (int i = 0; i < claimed.size(); i++) {
            RelayOutcome outcome = relayService.relay(claimed.get(i));
            if (outcome == RelayOutcome.PUBLISHED) {
                published++;
            } else if (outcome.isPublishFailure()) {
                List<OutboxEventEntity> rest = claimed.subList(i + 1, claimed.size());
                if (!rest.isEmpty()) {
                    log.info("Publish failed; releasing the remaining {} claimed outbox events until the next cycle",
                            rest.size());
                    claimer.release(rest);
                }
                break;
            } else if (outcome == RelayOutcome.SKIPPED) {
                // The skipped row was never attempted, so it goes back with the rest.
                List<OutboxEventEntity> rest = claimed.subList(i, claimed.size());
                log.info("Claim lease too short to publish safely; releasing the remaining {} claimed outbox"
                        + " events until the next cycle", rest.size());
                claimer.release(rest);
                break;
            }
        }
        return published;
    }

    /** Scheduled entry point; delegates to {@link #pollOnce()}. */
    @Scheduled(fixedDelayString = "${homefix.outbox-processor.poll-interval:PT1S}")
    public void poll() {
        try {
            pollOnce();
        } catch (RuntimeException ex) {
            // Never let a poll-cycle exception kill the scheduler thread; log and retry next cycle.
            // Rows this cycle claimed but did not finish become due again when their lease expires.
            log.error("Outbox poll cycle failed: {}", ex.getMessage(), ex);
        }
    }
}
