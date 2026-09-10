package com.homefix.outbox.relay;

import java.util.List;

import com.homefix.outbox.config.OutboxProcessorProperties;
import com.homefix.shared.outbox.OutboxEventEntity;
import com.homefix.shared.outbox.OutboxEventRepository;
import com.homefix.shared.outbox.OutboxEventStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically drains the transactional outbox (Requirement 22.4).
 *
 * <p>Each cycle fetches up to {@link OutboxProcessorProperties#getBatchSize()} PENDING rows in
 * insertion order (oldest first, preserving rough production order) and hands each to
 * {@link OutboxRelayService#relay(OutboxEventEntity)} for publication. The poll cadence is driven
 * by {@code @Scheduled} using {@code homefix.outbox-processor.poll-interval}.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventRepository repository;
    private final OutboxRelayService relayService;
    private final int batchSize;

    public OutboxPoller(OutboxEventRepository repository,
                        OutboxRelayService relayService,
                        OutboxProcessorProperties properties) {
        this.repository = repository;
        this.relayService = relayService;
        this.batchSize = properties.getBatchSize();
    }

    /**
     * Fetches a batch of PENDING rows and relays each. Returns the number of rows successfully
     * published so callers/tests can observe progress.
     */
    public int pollOnce() {
        List<OutboxEventEntity> pending = repository.findByStatusOrderByCreatedAtAsc(
                OutboxEventStatus.PENDING, PageRequest.of(0, batchSize));
        if (pending.isEmpty()) {
            return 0;
        }
        log.debug("Polled {} PENDING outbox events for relay", pending.size());
        int published = 0;
        for (OutboxEventEntity event : pending) {
            if (relayService.relay(event)) {
                published++;
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
            log.error("Outbox poll cycle failed: {}", ex.getMessage(), ex);
        }
    }
}
