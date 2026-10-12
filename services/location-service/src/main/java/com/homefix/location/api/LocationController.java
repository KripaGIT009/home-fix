package com.homefix.location.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.homefix.location.api.dto.LocationUpdateRequest;
import com.homefix.location.api.dto.LocationUpdateResponse;
import com.homefix.location.api.dto.TrackingViewResponse;
import com.homefix.location.domain.Coordinates;
import com.homefix.location.service.LocationAccessPolicy;
import com.homefix.location.service.LocationService;
import com.homefix.location.service.LocationUpdateCommand;
import com.homefix.location.subscription.SseSubscriberSession;
import com.homefix.location.subscription.SubscriberRegistryPort;

/**
 * HTTP surface for the Location Service (Requirement 10):
 * <ul>
 *   <li>{@code POST /locations/{bookingId}} — Provider GPS ingestion (Requirement 10.1);</li>
 *   <li>{@code GET  /locations/{bookingId}} — Customer tracking view with staleness flag
 *       (Requirement 10.3, 10.7);</li>
 *   <li>{@code GET  /locations/{bookingId}/stream} — Customer SSE subscription to the live feed
 *       (Requirement 10.2, 10.4).</li>
 * </ul>
 *
 * <p>Every endpoint checks ownership through {@link LocationAccessPolicy} before touching the feed:
 * a position is read only by the booking's customer, its assigned provider or staff, and posted only
 * by the assigned provider. RBAC alone admits any customer or provider with any booking id.
 */
@RestController
public class LocationController {

    private final LocationService locationService;
    private final SubscriberRegistryPort subscriberRegistry;
    private final LocationAccessPolicy accessPolicy;

    public LocationController(LocationService locationService,
                              SubscriberRegistryPort subscriberRegistry,
                              LocationAccessPolicy accessPolicy) {
        this.locationService = locationService;
        this.subscriberRegistry = subscriberRegistry;
        this.accessPolicy = accessPolicy;
    }

    /**
     * Records a position for the booking. The provider is the token's subject: a provider id in
     * the body is accepted for older clients but never trusted, so nobody can post positions in
     * another provider's name, and only the booking's assigned provider may post.
     */
    @PostMapping("/locations/{bookingId}")
    public ResponseEntity<LocationUpdateResponse> ingest(@PathVariable UUID bookingId,
                                                         @Valid @RequestBody LocationUpdateRequest request,
                                                         Authentication authentication) {
        UUID providerId = callerId(authentication);
        accessPolicy.requireAssignedProvider(bookingId, providerId);
        var command = new LocationUpdateCommand(
                bookingId,
                providerId,
                new Coordinates(request.latitude(), request.longitude()));
        var push = locationService.ingest(command);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(LocationUpdateResponse.from(push));
    }

    @GetMapping("/locations/{bookingId}")
    public ResponseEntity<TrackingViewResponse> trackingView(@PathVariable UUID bookingId,
                                                             Authentication authentication) {
        accessPolicy.requireReadAccess(bookingId, callerId(authentication), authentication.getAuthorities());
        var view = locationService.trackingView(bookingId);
        return ResponseEntity.ok(TrackingViewResponse.from(view));
    }

    /**
     * Opens a Server-Sent Events subscription to a Booking's live location feed. The session is
     * registered with the subscriber registry and deregistered on completion, timeout, or error
     * (subscription lifecycle management). Ownership is checked before the session is registered.
     */
    @GetMapping(path = "/locations/{bookingId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID bookingId, Authentication authentication) {
        accessPolicy.requireReadAccess(bookingId, callerId(authentication), authentication.getAuthorities());
        SseEmitter emitter = new SseEmitter(0L); // no timeout; terminated on JOB_STARTED or disconnect
        var session = new SseSubscriberSession(UUID.randomUUID().toString(), emitter);
        subscriberRegistry.register(bookingId, session);
        emitter.onCompletion(() -> subscriberRegistry.deregister(bookingId, session));
        emitter.onTimeout(() -> subscriberRegistry.deregister(bookingId, session));
        emitter.onError(ex -> subscriberRegistry.deregister(bookingId, session));
        return emitter;
    }

    private static UUID callerId(Authentication authentication) {
        try {
            return UUID.fromString(authentication.getName());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authenticated principal is not a user id");
        }
    }
}
