package com.homefix.booking.api;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.homefix.booking.api.dto.AddPartsRequest;
import com.homefix.booking.api.dto.BookingResponse;
import com.homefix.booking.api.dto.PauseJobRequest;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.media.MediaFile;
import com.homefix.booking.service.Actor;
import com.homefix.booking.service.AddPartsCommand;
import com.homefix.booking.service.BookingException;
import com.homefix.booking.service.JobExecutionService;
import com.homefix.booking.service.MediaService;

import jakarta.validation.Valid;

/**
 * Provider-facing job-execution milestone endpoints (Task 15; Requirements 9.3-9.11,
 * 11.2-11.6). Each endpoint funnels through {@link JobExecutionService}, which reuses the
 * shared state-machine/audit infrastructure.
 *
 * <p>Before/after photos are attached via {@link #attachPhoto} prior to the JOB_STARTED /
 * JOB_COMPLETED transitions so the photo requirement gates can be enforced.
 */
@RestController
@RequestMapping("/bookings/{reference}")
public class JobExecutionController {

    private final JobExecutionService jobExecution;
    private final MediaService mediaService;

    public JobExecutionController(JobExecutionService jobExecution, MediaService mediaService) {
        this.jobExecution = jobExecution;
        this.mediaService = mediaService;
    }

    /** {@code POST /bookings/{reference}/on-the-way} — PROVIDER_ON_THE_WAY (Requirement 9.3). */
    @PostMapping("/on-the-way")
    public ResponseEntity<BookingResponse> onTheWay(@PathVariable("reference") String reference,
                                                    Authentication auth) {
        return ok(jobExecution.markOnTheWay(reference, actor(auth)));
    }

    /** {@code POST /bookings/{reference}/arrived} — PROVIDER_ARRIVED (Requirement 9.4). */
    @PostMapping("/arrived")
    public ResponseEntity<BookingResponse> arrived(@PathVariable("reference") String reference,
                                                   Authentication auth) {
        return ok(jobExecution.markArrived(reference, actor(auth)));
    }

    /**
     * {@code POST /bookings/{reference}/photos} — attach a before/after photo (Requirement
     * 11.2, 11.4). {@code type} must be {@code BEFORE_PHOTO} or {@code AFTER_PHOTO}.
     */
    @PostMapping(path = "/photos", consumes = "multipart/form-data")
    public ResponseEntity<Void> attachPhoto(@PathVariable("reference") String reference,
                                            @RequestParam("type") String type,
                                            @RequestParam("file") MultipartFile file,
                                            Authentication auth) {
        String normalized = normalizePhotoType(type);
        Booking booking = jobExecution.requireForProvider(reference, actor(auth));
        mediaService.attach(booking.getId(), normalized, java.util.List.of(toMediaFile(file)));
        return ResponseEntity.noContent().build();
    }

    /** {@code POST /bookings/{reference}/start} — JOB_STARTED, requires before-photo (11.2). */
    @PostMapping("/start")
    public ResponseEntity<BookingResponse> start(@PathVariable("reference") String reference,
                                                 Authentication auth) {
        return ok(jobExecution.startJob(reference, actor(auth)));
    }

    /** {@code POST /bookings/{reference}/pause} — JOB_PAUSED, requires reason (11.5). */
    @PostMapping("/pause")
    public ResponseEntity<BookingResponse> pause(@PathVariable("reference") String reference,
                                                 @Valid @RequestBody PauseJobRequest request,
                                                 Authentication auth) {
        return ok(jobExecution.pauseJob(reference, actor(auth), request.reason()));
    }

    /** {@code POST /bookings/{reference}/resume} — JOB_PAUSED → JOB_STARTED (11.5). */
    @PostMapping("/resume")
    public ResponseEntity<BookingResponse> resume(@PathVariable("reference") String reference,
                                                  Authentication auth) {
        return ok(jobExecution.resumeJob(reference, actor(auth)));
    }

    /** {@code POST /bookings/{reference}/parts} — record parts and request approval (6.8, 11.3). */
    @PostMapping("/parts")
    public ResponseEntity<BookingResponse> addParts(@PathVariable("reference") String reference,
                                                    @Valid @RequestBody AddPartsRequest request,
                                                    Authentication auth) {
        AddPartsCommand cmd = new AddPartsCommand(request.itemName(), request.quantity(), request.unitCost());
        return ok(jobExecution.addParts(reference, actor(auth), cmd));
    }

    /** {@code POST /bookings/{reference}/quote/approval} — customer approves quote (9.7). */
    @PostMapping("/quote/approval")
    public ResponseEntity<BookingResponse> approveQuote(@PathVariable("reference") String reference,
                                                        Authentication auth) {
        return ok(jobExecution.approveAdditionalQuote(reference, actor(auth)));
    }

    /** {@code POST /bookings/{reference}/quote/rejection} — customer rejects quote (9.8). */
    @PostMapping("/quote/rejection")
    public ResponseEntity<BookingResponse> rejectQuote(@PathVariable("reference") String reference,
                                                       Authentication auth) {
        return ok(jobExecution.rejectAdditionalQuote(reference, actor(auth)));
    }

    /** {@code POST /bookings/{reference}/complete} — JOB_COMPLETED, requires after-photo (9.10, 11.4). */
    @PostMapping("/complete")
    public ResponseEntity<BookingResponse> complete(@PathVariable("reference") String reference,
                                                    Authentication auth) {
        return ok(jobExecution.completeJob(reference, actor(auth)));
    }

    // ----- helpers ---------------------------------------------------------

    private static ResponseEntity<BookingResponse> ok(Booking booking) {
        return ResponseEntity.ok(BookingResponse.of(booking));
    }

    private static String normalizePhotoType(String type) {
        if (type == null) {
            throw BookingException.validation("photo type is required (BEFORE_PHOTO or AFTER_PHOTO)");
        }
        String upper = type.trim().toUpperCase(java.util.Locale.ROOT);
        if (!JobExecutionService.BEFORE_PHOTO.equals(upper) && !JobExecutionService.AFTER_PHOTO.equals(upper)) {
            throw BookingException.validation("photo type must be BEFORE_PHOTO or AFTER_PHOTO");
        }
        return upper;
    }

    private static MediaFile toMediaFile(MultipartFile mf) {
        if (mf == null || mf.isEmpty()) {
            throw BookingException.media("photo file is required");
        }
        try {
            return new MediaFile(mf.getOriginalFilename(), mf.getContentType(), mf.getSize(), mf.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded photo", e);
        }
    }

    /** The caller, judged on all of the token's roles (see {@link CallerIdentity#actorOf}). */
    private static Actor actor(Authentication authentication) {
        return CallerIdentity.actorOf(authentication, "SERVICE_PROVIDER");
    }
}
