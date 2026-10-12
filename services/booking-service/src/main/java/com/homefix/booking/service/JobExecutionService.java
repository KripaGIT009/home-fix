package com.homefix.booking.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.booking.config.BookingProperties;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.domain.JobDurationCalculator;
import com.homefix.booking.domain.JobInterval;
import com.homefix.booking.domain.JobIntervalRepository;
import com.homefix.booking.domain.JobMediaRepository;
import com.homefix.booking.domain.PartsLineItem;
import com.homefix.booking.domain.PartsLineItemRepository;
import com.homefix.booking.event.JobExecutionEvents;
import com.homefix.booking.pricing.PartsRecalculationRequest;
import com.homefix.booking.pricing.PriceEstimate;
import com.homefix.booking.pricing.PricingClientPort;
import com.homefix.booking.pricing.PricingUnavailableException;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * Job-execution milestones and parts/materials flow (Task 15; Requirements 6.8, 9.3-9.11,
 * 11.2-11.6, 22.1; Property 10).
 *
 * <p>Every state change funnels through the existing {@link BookingTransitionService} so the
 * single state-machine enforcement point and contiguous audit trail from Task 14 are reused.
 * This service layers the job-execution concerns on top:
 * <ul>
 *   <li>before/after-photo requirement gates (11.2, 9.10/9.11/11.4);</li>
 *   <li>pause/resume with a mandatory reason and interval tracking (11.5);</li>
 *   <li>net job duration = Σ WORK − Σ PAUSE intervals, stored on completion (11.6, Property 10);</li>
 *   <li>parts/materials line items submitted to the Pricing Engine, driving
 *       ADDITIONAL_QUOTE_REQUIRED → CUSTOMER_APPROVAL_PENDING (6.8, 11.3);</li>
 *   <li>additional-quote approval timeout: auto-complete at the original price after the
 *       configured window (default 60 min) of no customer response (9.9);</li>
 *   <li>Kafka events ProviderArriving / ProviderArrived / JobStarted / JobCompleted (22.1).</li>
 * </ul>
 *
 * <p>Time-dependent logic (interval durations, approval timeout) uses the injected
 * {@link Clock} so tests are deterministic.
 */
@Service
public class JobExecutionService {

    private static final Logger log = LoggerFactory.getLogger(JobExecutionService.class);

    /** Logical media types checked by the photo gates. */
    public static final String BEFORE_PHOTO = "BEFORE_PHOTO";
    public static final String AFTER_PHOTO = "AFTER_PHOTO";

    private final BookingRepository bookingRepository;
    private final BookingTransitionService transitionService;
    private final JobIntervalRepository intervalRepository;
    private final JobMediaRepository mediaRepository;
    private final PartsLineItemRepository partsRepository;
    private final PricingClientPort pricingClient;
    private final OutboxEventPublisher outboxPublisher;
    private final BookingProperties properties;
    private final Clock clock;

    public JobExecutionService(BookingRepository bookingRepository,
                               BookingTransitionService transitionService,
                               JobIntervalRepository intervalRepository,
                               JobMediaRepository mediaRepository,
                               PartsLineItemRepository partsRepository,
                               PricingClientPort pricingClient,
                               OutboxEventPublisher outboxPublisher,
                               BookingProperties properties,
                               Clock clock) {
        this.bookingRepository = bookingRepository;
        this.transitionService = transitionService;
        this.intervalRepository = intervalRepository;
        this.mediaRepository = mediaRepository;
        this.partsRepository = partsRepository;
        this.pricingClient = pricingClient;
        this.outboxPublisher = outboxPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * PROVIDER_ACCEPTED → PROVIDER_ON_THE_WAY, publishing ProviderArriving (Requirement 9.3,
     * 22.1).
     */
    @Transactional
    public Booking markOnTheWay(String reference, Actor actor) {
        Booking booking = BookingAccess.requireForProvider(bookingRepository, reference, actor);
        transitionService.transition(booking, BookingStatus.PROVIDER_ON_THE_WAY, actor,
                "Provider on the way");
        publish(JobExecutionEvents.ProviderArriving.EVENT_TYPE, booking,
                new JobExecutionEvents.ProviderArriving(
                        booking.getId(), booking.getReference(), booking.getCustomerId(),
                        booking.getProviderId(), now()));
        return booking;
    }

    /**
     * PROVIDER_ON_THE_WAY → PROVIDER_ARRIVED, publishing ProviderArrived (Requirement 9.4,
     * 22.1).
     */
    @Transactional
    public Booking markArrived(String reference, Actor actor) {
        Booking booking = BookingAccess.requireForProvider(bookingRepository, reference, actor);
        transitionService.transition(booking, BookingStatus.PROVIDER_ARRIVED, actor,
                "Provider arrived");
        publish(JobExecutionEvents.ProviderArrived.EVENT_TYPE, booking,
                new JobExecutionEvents.ProviderArrived(
                        booking.getId(), booking.getReference(), booking.getCustomerId(),
                        booking.getProviderId(), now()));
        return booking;
    }

    /**
     * PROVIDER_ARRIVED → JOB_STARTED. Requires at least one before-photo (Requirement 11.2);
     * records the job start timestamp, opens the first WORK interval, and publishes JobStarted
     * (Requirement 9.5, 22.1).
     *
     * @throws BookingException 422 if no before-photo is attached
     */
    @Transactional
    public Booking startJob(String reference, Actor actor) {
        Booking booking = BookingAccess.requireForProvider(bookingRepository, reference, actor);
        requirePhoto(booking, BEFORE_PHOTO,
                "A before-photo must be attached before starting the job");

        Instant startAt = now();
        transitionService.transition(booking, BookingStatus.JOB_STARTED, actor, "Job started");
        if (booking.getStartedAt() == null) {
            booking.setStartedAt(startAt);
        }
        intervalRepository.save(JobInterval.work(booking.getId(), startAt));
        publish(JobExecutionEvents.JobStarted.EVENT_TYPE, booking,
                new JobExecutionEvents.JobStarted(
                        booking.getId(), booking.getReference(), booking.getCustomerId(),
                        booking.getProviderId(), booking.getStartedAt(), now()));
        return booking;
    }

    /**
     * JOB_STARTED → JOB_PAUSED. Requires a mandatory reason 1-500 chars (Requirement 11.5).
     * Closes the open WORK interval and opens a PAUSE interval.
     *
     * @throws BookingException 400 if the reason is missing or out of the 1-500 char range
     */
    @Transactional
    public Booking pauseJob(String reference, Actor actor, String reason) {
        String trimmed = reason == null ? null : reason.strip();
        if (trimmed == null || trimmed.isEmpty()) {
            throw BookingException.validation("A pause reason is required (1-500 characters)");
        }
        if (trimmed.length() > 500) {
            throw BookingException.validation("Pause reason must be at most 500 characters");
        }
        Booking booking = BookingAccess.requireForProvider(bookingRepository, reference, actor);
        Instant at = now();
        transitionService.transition(booking, BookingStatus.JOB_PAUSED, actor, trimmed);
        closeOpenInterval(booking.getId(), at);
        intervalRepository.save(JobInterval.pause(booking.getId(), at, trimmed));
        return booking;
    }

    /**
     * JOB_PAUSED → JOB_STARTED. Closes the open PAUSE interval and opens a new WORK interval
     * (Requirement 11.5). No new JobStarted event is published for a resume; the original
     * start timestamp is preserved.
     */
    @Transactional
    public Booking resumeJob(String reference, Actor actor) {
        Booking booking = BookingAccess.requireForProvider(bookingRepository, reference, actor);
        Instant at = now();
        transitionService.transition(booking, BookingStatus.JOB_STARTED, actor, "Job resumed");
        closeOpenInterval(booking.getId(), at);
        intervalRepository.save(JobInterval.work(booking.getId(), at));
        return booking;
    }

    /**
     * Records a parts/materials line item and drives the additional-quote flow (Requirement
     * 6.8, 11.3): validates the item, persists it, submits the aggregate parts total to the
     * Pricing Engine, then transitions JOB_STARTED → ADDITIONAL_QUOTE_REQUIRED →
     * CUSTOMER_APPROVAL_PENDING. The updated total is stored as the booking's final total
     * (pending customer approval); the original estimated total is retained for auto-resolution.
     *
     * @throws BookingException 400 on invalid item; 503 if the Pricing Engine is unavailable
     */
    @Transactional
    public Booking addParts(String reference, Actor actor, AddPartsCommand item) {
        validatePart(item);
        Booking booking = BookingAccess.requireForProvider(bookingRepository, reference, actor);

        PartsLineItem line = PartsLineItem.of(
                booking.getId(), item.itemName().strip(), item.quantity(), item.unitCost(), now());
        partsRepository.save(line);

        BigDecimal partsTotal = currentPartsTotal(booking.getId());
        PriceEstimate updated = recalculate(booking, partsTotal);

        // Close the open WORK interval before entering the approval wait so the time spent
        // awaiting customer approval is not counted as work, and so exactly one interval is
        // open at any time (approveAdditionalQuote opens a fresh WORK interval on resume).
        closeOpenInterval(booking.getId(), now());

        // JOB_STARTED -> ADDITIONAL_QUOTE_REQUIRED -> CUSTOMER_APPROVAL_PENDING (Requirement 9.6).
        transitionService.transition(booking, BookingStatus.ADDITIONAL_QUOTE_REQUIRED, actor,
                "Provider requested additional quote for parts/materials");
        transitionService.transition(booking, BookingStatus.CUSTOMER_APPROVAL_PENDING, Actor.system(),
                "Awaiting customer approval of additional quote");
        booking.setFinalTotal(updated.total());
        log.info("Booking {} additional quote pending: partsTotal={} updatedTotal={}",
                booking.getReference(), partsTotal, updated.total());
        return booking;
    }

    /**
     * Customer approves the additional quote: CUSTOMER_APPROVAL_PENDING → JOB_STARTED
     * (Requirement 9.7). Opens a new WORK interval so paused-for-approval time is not counted.
     */
    @Transactional
    public Booking approveAdditionalQuote(String reference, Actor actor) {
        Booking booking = BookingAccess.requireForCustomer(bookingRepository, reference, actor);
        transitionService.transition(booking, BookingStatus.JOB_STARTED, actor,
                "Customer approved additional quote");
        intervalRepository.save(JobInterval.work(booking.getId(), now()));
        return booking;
    }

    /**
     * Customer rejects the additional quote: CUSTOMER_APPROVAL_PENDING → JOB_COMPLETED at the
     * original pre-additional-quote price (Requirement 9.8). Reverts the final total to the
     * original estimate and completes the job.
     */
    @Transactional
    public Booking rejectAdditionalQuote(String reference, Actor actor) {
        Booking booking = BookingAccess.requireForCustomer(bookingRepository, reference, actor);
        booking.setFinalTotal(booking.getEstimatedTotal());
        completeInternal(booking, actor, "Customer rejected additional quote; completed at original price");
        return booking;
    }

    /**
     * Auto-resolves an additional quote that the customer has not responded to within the
     * configured window (default 60 min): CUSTOMER_APPROVAL_PENDING → JOB_COMPLETED at the
     * original price, with the system recorded as the actor (Requirement 9.9). No-op unless the
     * timeout has actually elapsed since entering CUSTOMER_APPROVAL_PENDING.
     *
     * @param pendingSince the instant the booking entered CUSTOMER_APPROVAL_PENDING
     * @return {@code true} if the booking was auto-completed
     */
    @Transactional
    public boolean autoResolveApprovalTimeout(String reference, Instant pendingSince) {
        Booking booking = require(reference);
        if (booking.getStatus() != BookingStatus.CUSTOMER_APPROVAL_PENDING) {
            return false;
        }
        Instant deadline = pendingSince.plus(properties.getAdditionalQuoteTimeout());
        if (now().isBefore(deadline)) {
            return false;
        }
        booking.setFinalTotal(booking.getEstimatedTotal());
        completeInternal(booking, Actor.system(),
                "No customer response within timeout; auto-completed at original price");
        log.info("Booking {} additional quote auto-resolved at original price after timeout",
                booking.getReference());
        return true;
    }

    /**
     * Marks the job complete: JOB_STARTED → JOB_COMPLETED. Requires at least one after-photo
     * (Requirement 9.10, 9.11, 11.4); computes and stores the net job duration (Requirement 11.6,
     * Property 10); publishes JobCompleted (22.1).
     *
     * <p>The state machine also permits CUSTOMER_APPROVAL_PENDING → JOB_COMPLETED, but only as the
     * customer's rejection of an additional quote or its timeout (Requirement 9.8, 9.9), both of
     * which revert the price to the original estimate. Letting the provider take that edge would
     * complete the job at the unapproved, parts-inclusive total, so the provider's completion is
     * refused there (409) like any other illegal transition: they wait for the customer's answer.
     *
     * @throws BookingException 422 if no after-photo is attached
     * @throws InvalidTransitionException 409 unless the booking is JOB_STARTED
     */
    @Transactional
    public Booking completeJob(String reference, Actor actor) {
        Booking booking = BookingAccess.requireForProvider(bookingRepository, reference, actor);
        if (booking.getStatus() != BookingStatus.JOB_STARTED) {
            log.warn("Rejected job completion bookingId={} from={} actorId={} actorRole={}",
                    booking.getId(), booking.getStatus(), actor.id(), actor.role());
            throw new InvalidTransitionException(booking.getId(), booking.getStatus(), BookingStatus.JOB_COMPLETED);
        }
        requirePhoto(booking, AFTER_PHOTO,
                "An after-photo must be attached before completing the job");
        completeInternal(booking, actor, "Job completed");
        return booking;
    }

    // ----- internals -------------------------------------------------------

    /** Shared completion path: close intervals, compute net duration, transition, publish. */
    private void completeInternal(Booking booking, Actor actor, String reason) {
        Instant completedAt = now();
        closeOpenInterval(booking.getId(), completedAt);

        transitionService.transition(booking, BookingStatus.JOB_COMPLETED, actor, reason);
        booking.setCompletedAt(completedAt);

        List<JobInterval> intervals = intervalRepository.findByBookingIdOrderByStartedAtAsc(booking.getId());
        long net = JobDurationCalculator.netDurationSeconds(intervals, completedAt);
        booking.setNetDurationSeconds((int) net);

        if (booking.getFinalTotal() == null) {
            booking.setFinalTotal(booking.getEstimatedTotal());
        }

        publish(JobExecutionEvents.JobCompleted.EVENT_TYPE, booking,
                new JobExecutionEvents.JobCompleted(
                        booking.getId(), booking.getReference(), booking.getCustomerId(),
                        booking.getProviderId(), completedAt, net, booking.getFinalTotal(), now()));
    }

    private void closeOpenInterval(java.util.UUID bookingId, Instant at) {
        Optional<JobInterval> open = intervalRepository.findByBookingIdAndEndedAtIsNull(bookingId);
        open.ifPresent(interval -> {
            interval.close(at);
            intervalRepository.save(interval);
        });
    }

    private void requirePhoto(Booking booking, String type, String message) {
        if (mediaRepository.countByBookingIdAndType(booking.getId(), type) < 1) {
            throw BookingException.photoRequired(message);
        }
    }

    private void validatePart(AddPartsCommand item) {
        if (item == null || item.itemName() == null || item.itemName().strip().isEmpty()) {
            throw BookingException.validation("Parts item name is required");
        }
        if (item.itemName().strip().length() > 200) {
            throw BookingException.validation("Parts item name must be at most 200 characters");
        }
        if (item.quantity() < 1) {
            throw BookingException.validation("Parts quantity must be at least 1");
        }
        if (item.unitCost() == null || item.unitCost().compareTo(new BigDecimal("0.01")) < 0) {
            throw BookingException.validation("Parts unit cost must be at least 0.01");
        }
    }

    private BigDecimal currentPartsTotal(java.util.UUID bookingId) {
        return partsRepository.findByBookingIdOrderByAddedAtAsc(bookingId).stream()
                .map(PartsLineItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private PriceEstimate recalculate(Booking booking, BigDecimal partsTotal) {
        try {
            return pricingClient.recalculateWithParts(new PartsRecalculationRequest(
                    booking.getId(), booking.getCategoryId(), booking.getSubcategoryId(),
                    booking.getEstimatedTotal(), partsTotal,
                    booking.isEmergency(), booking.getScheduledAt()));
        } catch (PricingUnavailableException ex) {
            log.warn("Pricing Engine unavailable during parts recalculation for booking {}: {}",
                    booking.getReference(), ex.getMessage());
            throw BookingException.pricingUnavailable();
        }
    }

    private void publish(String eventType, Booking booking, Object payload) {
        outboxPublisher.publish(JobExecutionEvents.AGGREGATE_TYPE, booking.getId(), eventType, payload);
    }

    /**
     * The booking for a provider-side call that is not itself a transition, such as attaching a
     * photo: same lookup and ownership rule as the milestones.
     */
    public Booking requireForProvider(String key, Actor actor) {
        return BookingAccess.requireForProvider(bookingRepository, key, actor);
    }

    private Booking require(String key) {
        return bookingRepository.findByKey(key)
                .orElseThrow(() -> BookingException.notFound(key));
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
