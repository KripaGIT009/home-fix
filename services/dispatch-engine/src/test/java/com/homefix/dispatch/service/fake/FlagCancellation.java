package com.homefix.dispatch.service.fake;

import com.homefix.dispatch.port.BookingCancellationPort;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** In-memory {@link BookingCancellationPort}: a booking is cancelled once a test marks it. */
public class FlagCancellation implements BookingCancellationPort {

    private final Set<UUID> cancelled = new HashSet<>();

    @Override
    public synchronized void markCancelled(UUID bookingId) {
        cancelled.add(bookingId);
    }

    @Override
    public synchronized boolean isCancelled(UUID bookingId) {
        return cancelled.contains(bookingId);
    }
}
