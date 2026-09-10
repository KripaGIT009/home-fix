package com.homefix.booking.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.homefix.booking.api.dto.BookingResponse;
import com.homefix.booking.api.dto.CancelBookingRequest;
import com.homefix.booking.api.dto.CreateBookingRequest;
import com.homefix.booking.media.MediaFile;
import com.homefix.booking.service.Actor;
import com.homefix.booking.service.BookingService;
import com.homefix.booking.service.CreateBookingCommand;

import jakarta.validation.Valid;

/**
 * Booking creation, confirmation, and cancellation endpoints (Requirements 7, 8.1, 9.16-9.18).
 *
 * <p>The authenticated customer is taken from the JWT-populated {@link Authentication}
 * (subject = user id, authorities = {@code ROLE_<role>}) rather than the request body, so a
 * customer can only create bookings for themselves.
 */
@RestController
@RequestMapping("/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * {@code POST /bookings} — create a scheduled or emergency booking. Media may be attached
     * as multipart parts. For scheduled bookings the response carries the itemized estimate to
     * present before confirmation (Requirement 7.3); the customer then calls
     * {@code POST /bookings/{reference}/confirmation}. Emergency bookings are created and moved
     * to SEARCHING_PROVIDER in this single call (Requirement 8.1).
     */
    @PostMapping
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody CreateBookingRequest request,
                                                  Authentication authentication) {
        UUID customerId = customerId(authentication);
        CreateBookingCommand cmd = new CreateBookingCommand(
                customerId, request.categoryId(), request.subcategoryId(), request.addressId(),
                request.emergency(), request.scheduledAt(), request.description(), List.of());
        BookingService.BookingCreationResult result = request.emergency()
                ? bookingService.createEmergency(cmd)
                : bookingService.createScheduled(cmd);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BookingResponse.withEstimate(result.booking(), result.estimate()));
    }

    /**
     * {@code POST /bookings/media} — multipart variant that accepts up to 10 media files
     * alongside the booking fields (Requirement 7.2). The media is validated and stored, and
     * the booking is created the same way as {@link #create}.
     */
    @PostMapping(path = "/media", consumes = "multipart/form-data")
    public ResponseEntity<BookingResponse> createWithMedia(
            @RequestParam("categoryId") UUID categoryId,
            @RequestParam("subcategoryId") UUID subcategoryId,
            @RequestParam(value = "addressId", required = false) UUID addressId,
            @RequestParam(value = "emergency", defaultValue = "false") boolean emergency,
            @RequestParam(value = "scheduledAt", required = false) String scheduledAt,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "media", required = false) MultipartFile[] media,
            Authentication authentication) {
        UUID customerId = customerId(authentication);
        CreateBookingCommand cmd = new CreateBookingCommand(
                customerId, categoryId, subcategoryId, addressId, emergency,
                scheduledAt == null ? null : java.time.Instant.parse(scheduledAt),
                description, toMediaFiles(media));
        BookingService.BookingCreationResult result = emergency
                ? bookingService.createEmergency(cmd)
                : bookingService.createScheduled(cmd);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BookingResponse.withEstimate(result.booking(), result.estimate()));
    }

    /**
     * {@code POST /bookings/{reference}/confirmation} — customer confirms the estimate; the
     * booking transitions to SEARCHING_PROVIDER and the BookingCreated event is published
     * (Requirement 7.5).
     */
    @PostMapping("/{reference}/confirmation")
    public ResponseEntity<BookingResponse> confirm(@PathVariable("reference") String reference,
                                                   Authentication authentication) {
        var booking = bookingService.confirm(reference, actor(authentication));
        return ResponseEntity.ok(BookingResponse.of(booking));
    }

    /**
     * {@code POST /bookings/{reference}/cancellation} — cancel a booking, applying the
     * cancellation-fee policy (Requirement 9.16-9.18).
     */
    @PostMapping("/{reference}/cancellation")
    public ResponseEntity<BookingResponse> cancel(@PathVariable("reference") String reference,
                                                  @Valid @RequestBody(required = false) CancelBookingRequest request,
                                                  Authentication authentication) {
        String reason = request == null ? null : request.reason();
        var booking = bookingService.cancel(reference, actor(authentication), reason);
        return ResponseEntity.ok(BookingResponse.of(booking));
    }

    // ----- helpers ---------------------------------------------------------

    private static UUID customerId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    private static Actor actor(Authentication authentication) {
        UUID id = UUID.fromString(authentication.getName());
        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring("ROLE_".length()) : a)
                .findFirst()
                .orElse("CUSTOMER");
        return Actor.user(id, role);
    }

    private static List<MediaFile> toMediaFiles(MultipartFile[] media) {
        if (media == null) {
            return List.of();
        }
        List<MediaFile> files = new ArrayList<>(media.length);
        for (MultipartFile mf : media) {
            if (mf == null || mf.isEmpty()) {
                continue;
            }
            try {
                files.add(new MediaFile(mf.getOriginalFilename(), mf.getContentType(),
                        mf.getSize(), mf.getBytes()));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read uploaded media", e);
            }
        }
        return files;
    }
}
