package com.homefix.dispatch.service;

import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.EnrichmentUnavailableException;
import com.homefix.dispatch.domain.UnresolvableBookingException;
import com.homefix.dispatch.event.BookingCreatedEvent;
import com.homefix.dispatch.port.CustomerAddressPort;
import com.homefix.dispatch.port.CustomerAddressPort.ResolvedAddress;
import com.homefix.dispatch.port.SubcategorySkillsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Completes the matching problem for a {@code BookingCreated} event before any provider is searched
 * for (Requirement 8.2).
 *
 * <p>The Booking Service publishes booking facts: the {@code addressId} and {@code subcategoryId}
 * the customer chose, not denormalised coordinates or skill tags. This step resolves the address to
 * coordinates through the Customer Service and the subcategory to its required skill tags through
 * the Service Catalog, so the event stays an honest record of the booking and each fact is read
 * from the service that owns it. A value the event already carries is used as-is and not looked up
 * again.
 *
 * <p>It runs synchronously on the Kafka listener thread, before the request is handed to a
 * bulkhead pool, so its failures reach the consumer rather than vanishing inside an asynchronous
 * task:
 * <ul>
 *   <li>{@link EnrichmentUnavailableException} — a dependency could not be consulted; retried.</li>
 *   <li>{@link UnresolvableBookingException} — the booking can never be matched as published (no
 *       address, an address that does not exist or belongs to another customer, a subcategory
 *       absent from the active catalog, or one with no skill tags configured); its message names
 *       what needs fixing, and the consumer fails the booking (SEARCHING_FAILED).</li>
 * </ul>
 */
@Service
public class BookingEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(BookingEnrichmentService.class);

    private final CustomerAddressPort customerAddress;
    private final SubcategorySkillsPort subcategorySkills;

    public BookingEnrichmentService(CustomerAddressPort customerAddress,
                                    SubcategorySkillsPort subcategorySkills) {
        this.customerAddress = customerAddress;
        this.subcategorySkills = subcategorySkills;
    }

    /**
     * Resolves whatever the event lacks and returns the complete dispatch problem.
     *
     * @throws UnresolvableBookingException  if the booking cannot be matched however often it is
     *                                       retried
     * @throws EnrichmentUnavailableException if a dependency could not be consulted
     */
    public DispatchRequest enrich(BookingCreatedEvent event) {
        double lat;
        double lon;
        if (event.customerLat() != null && event.customerLon() != null) {
            lat = event.customerLat();
            lon = event.customerLon();
        } else {
            ResolvedAddress address = resolveAddress(event);
            lat = address.lat();
            lon = address.lng();
        }

        List<String> skillTags = event.requiredSkillTags();
        if (skillTags == null || skillTags.isEmpty()) {
            skillTags = resolveSkillTags(event);
        }

        log.debug("Enriched booking {} for dispatch ({} skill tag(s))", event.bookingId(), skillTags.size());
        return new DispatchRequest(
                event.bookingId(),
                event.customerId(),
                lat,
                lon,
                event.subcategoryId(),
                skillTags,
                event.emergency(),
                event.occurredAt(),
                event.reference(),
                event.scheduledAt());
    }

    private ResolvedAddress resolveAddress(BookingCreatedEvent event) {
        if (event.addressId() == null) {
            // Booking-service accepts a booking without an address today; with no address there is
            // no location to search around, and searching around (0, 0) is what this step replaced.
            throw refused(event, "it carries neither customer coordinates nor an addressId, so there"
                    + " is no location to search for providers around");
        }
        ResolvedAddress address;
        try {
            address = customerAddress.lookup(event.addressId());
        } catch (UnresolvableBookingException notFound) {
            throw refused(event, notFound.getMessage());
        }
        if (event.customerId() != null && address.customerId() != null
                && !event.customerId().equals(address.customerId())) {
            // Never send a provider to somebody else's home because a booking named their address.
            throw refused(event, "address " + event.addressId() + " belongs to a different customer"
                    + " than the booking's customer " + event.customerId());
        }
        return address;
    }

    private List<String> resolveSkillTags(BookingCreatedEvent event) {
        if (event.subcategoryId() == null) {
            throw refused(event, "it carries no subcategoryId, so its required skills are unknown");
        }
        List<String> tags;
        try {
            tags = subcategorySkills.requiredSkillTags(event.subcategoryId());
        } catch (UnresolvableBookingException notFound) {
            throw refused(event, notFound.getMessage());
        }
        if (tags == null || tags.isEmpty()) {
            // Matching requires at least one shared skill tag; with none, either nobody qualifies or
            // (worse) everybody does. The catalog must configure the subcategory's tags.
            throw refused(event, "subcategory " + event.subcategoryId() + " has no skill tags"
                    + " configured in the Service Catalog, so no provider can be matched to it");
        }
        return tags;
    }

    private static UnresolvableBookingException refused(BookingCreatedEvent event, String why) {
        return new UnresolvableBookingException("BookingCreated for booking " + event.bookingId()
                + " cannot be dispatched: " + why + ". Dispatching on absent values is refused.");
    }
}
