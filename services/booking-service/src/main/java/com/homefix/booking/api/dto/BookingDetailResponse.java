package com.homefix.booking.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.homefix.booking.address.CustomerAddressPort.ServiceAddress;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.JobMedia;
import com.homefix.booking.domain.PartsLineItem;
import com.homefix.booking.service.BookingQueryService;
import com.homefix.booking.service.JobExecutionService;

/**
 * A single booking as the customer app's detail and tracking screens render it,
 * {@code GET /bookings/{bookingId}} (Requirement 28.7).
 *
 * <p>The first seven fields are the app's {@code BookingDetail} contract, derived the same way
 * as a history row. The rest are what a tracking screen needs to show progress without a second
 * call: whether it is an emergency, when it is scheduled and was placed, who is assigned, and the
 * subcategory id behind {@code serviceName} (so a client can resolve the name itself when the
 * label is the {@value BookingQueryService#FALLBACK_SERVICE_NAME} fallback).
 *
 * <p>The provider app reads the same endpoint for its job screens (Requirement 11.1-11.6), so
 * the detail also carries the job's {@code address} and {@code coordinates} (resolved from the
 * Customer Service, omitted when it cannot be reached), the before/after {@code photos} that gate
 * starting and completing the job, the {@code parts} recorded, and once the job is done its
 * {@code netDurationSeconds}. Only the booking's customer, its assigned provider and staff can
 * read a detail at all.
 *
 * <p>While a Tenant-assigned booking waits for the Provider's confirmation (PROVIDER_ASSIGNED),
 * {@code tenantName} names the agency that assigned it, for the provider app's "Assigned by ..."
 * and the customer app's partner copy (Requirement MT-6.4, MT-13.1); it is absent otherwise.
 *
 * <h2>Deliberately absent</h2>
 * The customer app's contract also allows {@code description}, {@code provider} and
 * {@code invoice}. None is sent: the provider's name, rating and verification live in the Provider
 * Service and the invoice in the Invoice Service, which this service has no client for, and the
 * booking description is accepted at creation but not persisted. Null fields are omitted, so an
 * unassigned booking has no {@code providerId} key at all.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookingDetailResponse(
        UUID bookingId,
        String referenceNumber,
        String status,
        String serviceName,
        Instant date,
        BigDecimal amount,
        String currency,
        boolean emergency,
        Instant scheduledAt,
        Instant createdAt,
        UUID providerId,
        UUID subcategoryId,
        String address,
        Coordinates coordinates,
        List<Photo> photos,
        List<Part> parts,
        Integer netDurationSeconds,
        String tenantName) {

    /** Where the job is, for the provider's navigation link. */
    public record Coordinates(double latitude, double longitude) {
    }

    /**
     * A before/after photo the provider attached. {@code kind} is {@code BEFORE} or {@code AFTER};
     * there is no URL because stored media is not served back yet.
     */
    public record Photo(UUID id, String kind, Instant uploadedAt) {

        static Photo of(JobMedia media) {
            String kind = JobExecutionService.BEFORE_PHOTO.equals(media.getType()) ? "BEFORE" : "AFTER";
            return new Photo(media.getId(), kind, media.getUploadedAt());
        }
    }

    /** A parts/materials line item (Requirement 11.3). */
    public record Part(UUID id, String itemName, int quantity, BigDecimal unitCost) {

        static Part of(PartsLineItem item) {
            return new Part(item.getId(), item.getItemName(), item.getQuantity(), item.getUnitCost());
        }
    }

    public static BookingDetailResponse of(BookingQueryService.BookingView view) {
        Booking b = view.booking();
        BookingQueryService.JobFacts job = view.job();
        ServiceAddress address = job == null ? null : job.address();
        return new BookingDetailResponse(
                b.getId(),
                b.getReference(),
                b.getStatus().name(),
                view.serviceName(),
                view.date(),
                view.amount(),
                BookingHistoryPageResponse.CURRENCY,
                b.isEmergency(),
                b.getScheduledAt(),
                b.getCreatedAt(),
                b.getProviderId(),
                b.getSubcategoryId(),
                address == null ? null : address.label(),
                address == null ? null : new Coordinates(address.latitude(), address.longitude()),
                job == null ? null : job.photos().stream().map(Photo::of).toList(),
                job == null ? null : job.parts().stream().map(Part::of).toList(),
                b.getNetDurationSeconds(),
                job == null ? null : job.tenantName());
    }
}
