package com.homefix.booking.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.homefix.booking.service.BookingQueryService;

/**
 * One page of the caller's service history, {@code GET /bookings/history} (Requirement 28.7).
 * Field names follow the customer app's {@code BookingHistoryPage}; {@code page} is 1-based.
 */
public record BookingHistoryPageResponse(
        List<Item> items,
        int page,
        int pageSize,
        long totalItems,
        int totalPages) {

    /** Every amount the Booking Service records is in the platform currency. */
    public static final String CURRENCY = "INR";

    public static BookingHistoryPageResponse of(BookingQueryService.HistoryPage page) {
        return new BookingHistoryPageResponse(
                page.items().stream().map(Item::of).toList(),
                page.page(), page.pageSize(), page.totalItems(), page.totalPages());
    }

    /**
     * A history row. {@code date} is the scheduled time, else the creation time; {@code amount} is
     * the final total, else the estimate (see {@link BookingQueryService.BookingView}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            UUID bookingId,
            String referenceNumber,
            String status,
            String serviceName,
            Instant date,
            BigDecimal amount,
            String currency) {

        static Item of(BookingQueryService.BookingView view) {
            return new Item(
                    view.booking().getId(),
                    view.booking().getReference(),
                    view.booking().getStatus().name(),
                    view.serviceName(),
                    view.date(),
                    view.amount(),
                    CURRENCY);
        }
    }
}
