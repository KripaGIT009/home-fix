package com.homefix.booking.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.homefix.booking.address.CustomerAddressPort;
import com.homefix.booking.address.CustomerAddressPort.ServiceAddress;
import com.homefix.booking.catalog.CatalogClientPort;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.domain.JobMedia;
import com.homefix.booking.domain.JobMediaRepository;
import com.homefix.booking.domain.PartsLineItem;
import com.homefix.booking.domain.PartsLineItemRepository;
import com.homefix.booking.tenant.TenantDirectoryPort;
import com.homefix.booking.tenant.TenantDirectoryPort.TenantSummary;

/**
 * Read side of the booking aggregate: a customer's service history and the detail of a single
 * booking (Requirement 28.7), which the customer app's history and tracking screens render.
 *
 * <h2>Who may read what</h2>
 * <ul>
 *   <li><b>History</b> is always the caller's own: it is keyed by the JWT subject, never by a
 *       parameter, so there is no id to swap. Staff get their own (normally empty) history here —
 *       listing other people's bookings is an admin concern and does not belong on this path.</li>
 *   <li><b>Detail</b> is visible to the booking's customer, its assigned provider, and staff.
 *       Anyone else gets the same 404 as for a booking that does not exist, not a 403: a 403
 *       would confirm the id or reference is real, letting a caller probe for live bookings.</li>
 *   <li><b>The admin list</b> ({@link #adminSearch}) spans every customer. It is served only on
 *       {@code /admin/bookings}, which {@code BookingRbacConfig} restricts to staff.</li>
 * </ul>
 *
 * <h2>Why there is no {@code @Transactional} here</h2>
 * {@code Booking} has no lazy associations, so nothing needs a session after the repository call
 * returns, and each repository method already runs in its own read-only transaction. Holding one
 * open across the whole method would keep a pooled connection checked out for the catalog HTTP
 * call that follows the read — the pattern the review flagged on the create path.
 */
@Service
public class BookingQueryService {

    private static final Logger log = LoggerFactory.getLogger(BookingQueryService.class);

    /** Page size used when the caller does not ask for one; matches the customer app's default. */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** Largest page a caller may request, bounding the cost of a single history read. */
    public static final int MAX_PAGE_SIZE = 50;

    /**
     * Most bookings the Admin Portal's list returns. The portal's table takes one bare array with
     * no paging, so the bound is enforced here; a staff user narrows a larger result by searching.
     */
    public static final int ADMIN_LIST_LIMIT = 200;

    /**
     * Label shown when the subcategory's name cannot be resolved — catalog unreachable, or the
     * subcategory deactivated since the booking was placed. A read never fails over a label.
     */
    public static final String FALLBACK_SERVICE_NAME = "Service";

    /** Media types the provider attaches while doing the job, as opposed to the customer's own. */
    private static final Set<String> JOB_PHOTO_TYPES =
            Set.of(JobExecutionService.BEFORE_PHOTO, JobExecutionService.AFTER_PHOTO);

    private final BookingRepository bookingRepository;
    private final CatalogClientPort catalogClient;
    private final JobMediaRepository mediaRepository;
    private final PartsLineItemRepository partsRepository;
    private final CustomerAddressPort addresses;
    private final TenantDirectoryPort tenantDirectory;

    @Autowired
    public BookingQueryService(BookingRepository bookingRepository,
                               CatalogClientPort catalogClient,
                               JobMediaRepository mediaRepository,
                               PartsLineItemRepository partsRepository,
                               CustomerAddressPort addresses,
                               TenantDirectoryPort tenantDirectory) {
        this.bookingRepository = bookingRepository;
        this.catalogClient = catalogClient;
        this.mediaRepository = mediaRepository;
        this.partsRepository = partsRepository;
        this.addresses = addresses;
        this.tenantDirectory = tenantDirectory;
    }

    /**
     * For read-side tests that involve no Tenant: Tenant names are never resolved, so a detail
     * carries no {@code tenantName}.
     */
    public BookingQueryService(BookingRepository bookingRepository,
                               CatalogClientPort catalogClient,
                               JobMediaRepository mediaRepository,
                               PartsLineItemRepository partsRepository,
                               CustomerAddressPort addresses) {
        this(bookingRepository, catalogClient, mediaRepository, partsRepository, addresses, null);
    }

    /**
     * One page of {@code customerId}'s bookings, newest first.
     *
     * <p>{@code page} is <b>1-based</b>, in the request and in the result: the customer app keeps
     * the page number of its pagination control (which starts at 1) and sends it unchanged, so
     * a 0-based API would make the app skip the first page. A page past the end is not an error;
     * it comes back empty with the real totals, so the client can correct its position.
     *
     * @throws BookingException 400 when {@code page < 1} or {@code pageSize} is outside
     *         1..{@value #MAX_PAGE_SIZE}
     */
    public HistoryPage history(UUID customerId, int page, int pageSize) {
        if (page < 1) {
            throw BookingException.validation("page must be 1 or greater");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw BookingException.validation("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        // The row offset is handed to the database as an int; refuse a page whose offset would
        // overflow it rather than letting the conversion fail as a 500.
        if ((long) (page - 1) * pageSize > Integer.MAX_VALUE) {
            throw BookingException.validation("page is out of range");
        }

        Page<Booking> result = bookingRepository.findByCustomerIdOrderByCreatedAtDescIdDesc(
                customerId, PageRequest.of(page - 1, pageSize));
        // An empty page needs no labels, which spares the catalog call for most staff callers
        // and for every new customer.
        Map<UUID, String> names = result.isEmpty() ? Map.of() : subcategoryNames();
        List<BookingView> items = result.getContent().stream()
                .map(booking -> new BookingView(booking, serviceName(booking, names)))
                .toList();
        return new HistoryPage(items, page, pageSize, result.getTotalElements(), result.getTotalPages());
    }

    /**
     * The booking identified by {@code bookingKey}, if {@code callerId} may see it.
     *
     * <p>{@code bookingKey} is the booking's UUID or its reference
     * ({@link BookingRepository#findByKey}).
     *
     * @param staff whether the caller holds a staff role, and so may read any booking
     * @throws BookingException 404 when no such booking exists <em>or</em> the caller is neither
     *         its customer, its assigned provider, nor staff — deliberately indistinguishable
     */
    public BookingView detail(String bookingKey, UUID callerId, boolean staff) {
        Booking booking = bookingRepository.findByKey(bookingKey)
                .filter(b -> staff
                        || callerId.equals(b.getCustomerId())
                        || callerId.equals(b.getProviderId()))
                .orElseThrow(() -> BookingException.notFound(bookingKey));
        return new BookingView(booking, serviceName(booking, subcategoryNames()), jobFacts(booking));
    }

    /**
     * Bookings across all customers for the Admin Portal's booking list (Requirement 19.2), newest
     * first and capped at {@value #ADMIN_LIST_LIMIT}. Callers are staff — {@code BookingRbacConfig}
     * admits no one else to {@code /admin/bookings} — so there is no ownership filter here.
     *
     * <p>{@code search} is matched as a case-insensitive substring of the reference; a search that
     * is a booking UUID finds that booking instead. The two cannot overlap: a reference
     * ({@code HFX-yyyyMMdd-XXXXXX}) is shorter than a UUID, so no reference contains one.
     *
     * @param search optional; blank lists everything
     * @param status optional; null lists every status
     */
    public List<BookingView> adminSearch(String search, BookingStatus status) {
        String term = search == null ? "" : search.strip();
        Collection<BookingStatus> statuses = status == null
                ? EnumSet.allOf(BookingStatus.class)
                : EnumSet.of(status);

        List<Booking> bookings;
        UUID id = term.isEmpty() ? null : canonicalUuid(term);
        if (id != null) {
            bookings = bookingRepository.findById(id)
                    .filter(b -> statuses.contains(b.getStatus()))
                    .map(List::of)
                    .orElse(List.of());
        } else {
            bookings = bookingRepository.findByReferenceContainingIgnoreCaseAndStatusInOrderByCreatedAtDescIdDesc(
                    term, statuses, PageRequest.of(0, ADMIN_LIST_LIMIT));
        }
        // One catalog call per list, as for a history page, and none for an empty result.
        Map<UUID, String> names = bookings.isEmpty() ? Map.of() : subcategoryNames();
        return bookings.stream()
                .map(booking -> new BookingView(booking, serviceName(booking, names)))
                .toList();
    }

    /**
     * {@code booking} with its service label, for a command response that renders the same row as
     * {@link #adminSearch} (e.g. the Admin Portal's force-cancel).
     */
    public BookingView labelled(Booking booking) {
        return new BookingView(booking, serviceName(booking, subcategoryNames()));
    }

    /**
     * {@code bookings} with their service labels, from one catalog call (none for an empty list),
     * for the Tenant Portal's queue and job list.
     */
    public List<BookingView> labelled(List<Booking> bookings) {
        Map<UUID, String> names = bookings.isEmpty() ? Map.of() : subcategoryNames();
        return bookings.stream()
                .map(booking -> new BookingView(booking, serviceName(booking, names)))
                .toList();
    }

    // ----- internals -------------------------------------------------------

    /** {@code value} as a UUID only in its canonical 36-character form, else {@code null}. */
    private static UUID canonicalUuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equalsIgnoreCase(value) ? id : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Subcategory names from the catalog. The port already answers an empty map when the catalog
     * is down; this guards against an adapter that throws instead, since a label must never fail
     * the read.
     */
    /**
     * What the provider's job screens need beyond the booking row (Requirement 11.1-11.6): where
     * the job is, the before/after photos attached so far — the start and complete buttons are
     * gated on them — and the parts recorded. The address comes from the Customer Service and is
     * simply absent when it cannot be resolved; a label never fails a read.
     */
    private JobFacts jobFacts(Booking booking) {
        List<JobMedia> photos = mediaRepository.findByBookingId(booking.getId()).stream()
                .filter(m -> JOB_PHOTO_TYPES.contains(m.getType()))
                .sorted(Comparator.comparing(JobMedia::getUploadedAt))
                .toList();
        return new JobFacts(
                addresses.find(booking.getAddressId()).orElse(null),
                photos,
                partsRepository.findByBookingIdOrderByAddedAtAsc(booking.getId()),
                assigningTenantName(booking));
    }

    /**
     * The name of the Tenant that assigned the job, while the Provider has yet to confirm it — what
     * the provider app shows as "Assigned by ..." and the customer app as the partner assigning a
     * professional (Requirement MT-6.4, MT-13.1). Looked up only in PROVIDER_ASSIGNED, so the
     * Provider Service is not asked on every tracking poll of a running job. A label: an unknown
     * Tenant or an unreachable Provider Service simply leaves it out.
     */
    private String assigningTenantName(Booking booking) {
        if (tenantDirectory == null
                || booking.getTenantId() == null
                || booking.getStatus() != BookingStatus.PROVIDER_ASSIGNED) {
            return null;
        }
        try {
            return tenantDirectory.byId(booking.getTenantId()).map(TenantSummary::name).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Tenant {} name unavailable for booking {}: {}",
                    booking.getTenantId(), booking.getId(), e.getMessage());
            return null;
        }
    }

    private Map<UUID, String> subcategoryNames() {
        try {
            Map<UUID, String> names = catalogClient.subcategoryNames();
            return names == null ? Map.of() : names;
        } catch (RuntimeException e) {
            log.warn("Subcategory names unavailable; labelling bookings '{}': {}",
                    FALLBACK_SERVICE_NAME, e.getMessage());
            return Map.of();
        }
    }

    private static String serviceName(Booking booking, Map<UUID, String> names) {
        String name = names.get(booking.getSubcategoryId());
        return name == null || name.isBlank() ? FALLBACK_SERVICE_NAME : name;
    }

    /**
     * A booking together with its resolved service label, plus the two display values both read
     * endpoints derive the same way. {@code job} is filled for a single booking's detail only;
     * history rows leave it null.
     */
    public record BookingView(Booking booking, String serviceName, JobFacts job) {

        public BookingView(Booking booking, String serviceName) {
            this(booking, serviceName, null);
        }

        /**
         * The date a customer thinks of the booking by: when the work is scheduled for, falling
         * back to when it was placed. Emergency bookings are scheduled at creation, so this is
         * never null.
         */
        public Instant date() {
            return booking.getScheduledAt() != null ? booking.getScheduledAt() : booking.getCreatedAt();
        }

        /** What the booking costs; see {@link Booking#payableTotal()}. */
        public BigDecimal amount() {
            return booking.payableTotal();
        }
    }

    /**
     * The job-execution side of a booking's detail.
     *
     * @param address where the job is; null when the Customer Service cannot resolve it
     * @param photos  before/after photos, oldest first
     * @param parts   parts and materials recorded, oldest first
     * @param tenantName the Tenant that assigned the job, while PROVIDER_ASSIGNED; null otherwise
     */
    public record JobFacts(ServiceAddress address, List<JobMedia> photos, List<PartsLineItem> parts,
                           String tenantName) {
    }

    /** One page of history; {@code page} is 1-based (see {@link #history}). */
    public record HistoryPage(List<BookingView> items, int page, int pageSize,
                              long totalItems, int totalPages) {
    }
}
