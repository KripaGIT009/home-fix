package com.homefix.booking.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.booking.catalog.CatalogClientPort;
import com.homefix.booking.config.BookingProperties;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.event.BookingCreatedEvent;
import com.homefix.booking.pricing.PriceEstimate;
import com.homefix.booking.pricing.PriceEstimateRequest;
import com.homefix.booking.pricing.PricingClientPort;
import com.homefix.booking.pricing.PricingUnavailableException;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * Application service for booking creation, confirmation, cancellation, and generic state
 * transitions (Requirements 7, 8.1, 9).
 *
 * <p>Creation validates the requested subcategory is active (Requirement 7.6), enforces the
 * scheduled lead-time and horizon bounds (Requirement 7.7-7.8), and obtains an itemized price
 * estimate from the Pricing Engine (Requirement 7.3). If the Pricing Engine is unavailable no
 * booking is created and a 503 is returned (Requirement 7.4). Confirmation drives the booking
 * to SEARCHING_PROVIDER and publishes {@code BookingCreated} to the outbox (Requirement 7.5),
 * wrapped in the Saga orchestrator (Requirement 24.6-24.7).
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final CatalogClientPort catalogClient;
    private final PricingClientPort pricingClient;
    private final MediaService mediaService;
    private final BookingTransitionService transitionService;
    private final BookingSagaOrchestrator sagaOrchestrator;
    private final BookingReferenceGenerator referenceGenerator;
    private final OutboxEventPublisher outboxPublisher;
    private final CancellationFeePolicy cancellationFeePolicy;
    private final BookingProperties properties;
    private final Clock clock;

    public BookingService(BookingRepository bookingRepository,
                          CatalogClientPort catalogClient,
                          PricingClientPort pricingClient,
                          MediaService mediaService,
                          BookingTransitionService transitionService,
                          BookingSagaOrchestrator sagaOrchestrator,
                          BookingReferenceGenerator referenceGenerator,
                          OutboxEventPublisher outboxPublisher,
                          CancellationFeePolicy cancellationFeePolicy,
                          BookingProperties properties,
                          Clock clock) {
        this.bookingRepository = bookingRepository;
        this.catalogClient = catalogClient;
        this.pricingClient = pricingClient;
        this.mediaService = mediaService;
        this.transitionService = transitionService;
        this.sagaOrchestrator = sagaOrchestrator;
        this.referenceGenerator = referenceGenerator;
        this.outboxPublisher = outboxPublisher;
        this.cancellationFeePolicy = cancellationFeePolicy;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Creates a scheduled booking in CREATED status and returns the created booking together
     * with the itemized price estimate to present to the customer before confirmation
     * (Requirement 7.1, 7.3). Media (if any) is validated and stored (Requirement 7.2).
     *
     * @throws BookingException 422 if the subcategory is inactive / lead time or horizon
     *         violated; 503 if the Pricing Engine is unavailable (no booking created)
     */
    @Transactional
    public BookingCreationResult createScheduled(CreateBookingCommand cmd) {
        validateSubcategoryActive(cmd);
        validateSchedule(cmd.scheduledAt());
        PriceEstimate estimate = requestEstimate(cmd, cmd.scheduledAt());
        Booking booking = persistNewBooking(cmd, cmd.scheduledAt(), estimate.total());
        mediaService.attach(booking.getId(), "CUSTOMER_UPLOAD", cmd.media());
        return new BookingCreationResult(booking, estimate);
    }

    /**
     * Confirms a scheduled booking: transitions CREATED -> SEARCHING_PROVIDER and publishes
     * the {@code BookingCreated} event (Requirement 7.5), orchestrated as a Saga
     * (Requirement 24.6-24.7).
     *
     * @throws InvalidTransitionException if the booking is not in CREATED (mapped to 409)
     */
    @Transactional
    public Booking confirm(String reference, Actor actor) {
        Booking booking = BookingAccess.requireForCustomer(bookingRepository, reference, actor);
        beginSearchingProvider(booking, actor);
        return booking;
    }

    /**
     * Creates an emergency booking and immediately drives it to SEARCHING_PROVIDER in a single
     * call (Requirement 8.1). Creation and the transition run within the same request so both
     * SLAs (2 s create, +3 s SEARCHING_PROVIDER) are met without an interactive confirmation.
     *
     * @throws BookingException 503 if the Pricing Engine is unavailable (no booking created)
     */
    @Transactional
    public BookingCreationResult createEmergency(CreateBookingCommand cmd) {
        validateSubcategoryActive(cmd);
        // Emergency bookings are for "now"; scheduledAt is the current instant.
        Instant now = Instant.now(clock);
        PriceEstimate estimate = requestEstimate(cmd, now);
        Booking booking = persistNewBooking(cmd, now, estimate.total());
        mediaService.attach(booking.getId(), "CUSTOMER_UPLOAD", cmd.media());
        Actor system = Actor.system();
        beginSearchingProvider(booking, system);
        return new BookingCreationResult(booking, estimate);
    }

    /**
     * Cancels a booking, applying the cancellation-fee policy (Requirement 9.16-9.18):
     * no fee before PROVIDER_ON_THE_WAY; the configured subcategory fee (0.00-999.99) once the
     * provider is on the way or later. The transition to CANCELLED is validated by the state
     * machine (invalid source states yield 409).
     *
     * <p>The fee is set before the transition so the {@code BookingCancelled} event the
     * transition writes to the outbox (Requirement 22.1) carries it. A rejected transition
     * throws and rolls the whole transaction back, fee included.
     */
    @Transactional
    public Booking cancel(String reference, Actor actor, String reason) {
        Booking booking = BookingAccess.requireForParticipant(bookingRepository, reference, actor);
        BigDecimal fee = cancellationFeePolicy.feeFor(booking);
        booking.setCancellationFee(fee);
        transitionService.transition(booking, BookingStatus.CANCELLED, actor, reason);
        log.info("Cancelled booking {} with fee={}", booking.getReference(), fee);
        return booking;
    }

    /**
     * Generic guarded transition used by dispatch/job-execution callers and tests. Validates
     * against the state machine and records the audit entry.
     */
    @Transactional
    public Booking transition(String reference, BookingStatus target, Actor actor, String reason) {
        Booking booking = requireBooking(reference);
        return transitionService.transition(booking, target, actor, reason);
    }

    // ----- internals -------------------------------------------------------

    /** Drives CREATED -> SEARCHING_PROVIDER and publishes BookingCreated as a Saga. */
    private void beginSearchingProvider(Booking booking, Actor actor) {
        sagaOrchestrator.execute(booking.getId(), List.of(
                BookingSagaOrchestrator.Step.of(
                        "transition-to-searching-provider",
                        () -> transitionService.transition(
                                booking, BookingStatus.SEARCHING_PROVIDER, actor,
                                "Customer confirmed booking and price estimate"),
                        // Compensation: revert to CREATED if a later step fails.
                        b -> booking.applyStatus(BookingStatus.CREATED)),
                BookingSagaOrchestrator.Step.of(
                        "publish-booking-created",
                        () -> publishBookingCreated(booking))
        ));
    }

    private UUID publishBookingCreated(Booking booking) {
        BookingCreatedEvent event = new BookingCreatedEvent(
                booking.getId(), booking.getReference(), booking.getCustomerId(),
                booking.getCategoryId(), booking.getSubcategoryId(), booking.getAddressId(),
                booking.isEmergency(), booking.getScheduledAt(), Instant.now(clock));
        return outboxPublisher.publish(
                BookingCreatedEvent.AGGREGATE_TYPE, booking.getId(),
                BookingCreatedEvent.EVENT_TYPE, event);
    }

    private void validateSubcategoryActive(CreateBookingCommand cmd) {
        if (!catalogClient.isSubcategoryActive(cmd.categoryId(), cmd.subcategoryId())) {
            throw BookingException.serviceUnavailable(
                    "The requested service is not available: subcategory " + cmd.subcategoryId());
        }
    }

    private void validateSchedule(Instant scheduledAt) {
        if (scheduledAt == null) {
            throw BookingException.validation("scheduledAt is required for a scheduled booking");
        }
        Instant now = Instant.now(clock);
        Instant earliest = now.plus(properties.getMinLeadTime());
        if (scheduledAt.isBefore(earliest)) {
            throw BookingException.leadTime(
                    "Scheduled time must be at least " + properties.getMinLeadTime().toHours()
                            + " hours from now");
        }
        Instant latest = now.plus(properties.getMaxHorizon());
        if (scheduledAt.isAfter(latest)) {
            throw BookingException.horizon(
                    "Scheduled time must be within " + properties.getMaxHorizon().toDays()
                            + " days from now");
        }
    }

    /** Requests the estimate; maps Pricing Engine unavailability to 503 with no booking (7.4). */
    private PriceEstimate requestEstimate(CreateBookingCommand cmd, Instant scheduledAt) {
        try {
            return pricingClient.estimate(new PriceEstimateRequest(
                    cmd.categoryId(), cmd.subcategoryId(), cmd.customerId(),
                    cmd.emergency(), scheduledAt, cmd.couponCode()));
        } catch (PricingUnavailableException ex) {
            log.warn("Pricing Engine unavailable; booking not created: {}", ex.getMessage());
            throw BookingException.pricingUnavailable();
        }
    }

    private Booking persistNewBooking(CreateBookingCommand cmd, Instant scheduledAt, BigDecimal estimatedTotal) {
        String reference = referenceGenerator.generate();
        Booking booking = Booking.create(reference, cmd.customerId(), cmd.categoryId(),
                cmd.subcategoryId(), cmd.addressId(), cmd.emergency(), scheduledAt, estimatedTotal);
        // Booking has an application-assigned id + a primitive @Version, so Spring Data JPA
        // treats it as non-new and performs a merge(), returning a *managed* copy while the
        // passed instance stays detached. Downstream steps in the same transaction (the
        // emergency create-then-SEARCHING_PROVIDER saga) must operate on the managed instance
        // so their status change is dirty-checked and flushed; otherwise the transition is
        // silently lost on commit.
        Booking managed = bookingRepository.save(booking);
        transitionService.recordCreation(managed, actorForCreation(cmd), "Booking created");
        log.info("Created booking {} emergency={} status={}",
                reference, cmd.emergency(), managed.getStatus());
        return managed;
    }

    private Actor actorForCreation(CreateBookingCommand cmd) {
        return Actor.user(cmd.customerId(), "CUSTOMER");
    }

    private Booking requireBooking(String reference) {
        return bookingRepository.findByKey(reference)
                .orElseThrow(() -> BookingException.notFound(reference));
    }

    /** Result of a create call: the persisted booking plus the itemized estimate to present. */
    public record BookingCreationResult(Booking booking, PriceEstimate estimate) {
    }
}
