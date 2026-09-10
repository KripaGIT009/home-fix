package com.homefix.rating.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.homefix.rating.alert.AdminAlertPort;

/**
 * Recording {@link AdminAlertPort} test double: captures the providers alerted below threshold so
 * tests can assert an alert fired exactly once.
 */
public class RecordingAdminAlertPort implements AdminAlertPort {

    /** Provider IDs alerted, in order (duplicates would indicate a re-alert bug). */
    public final List<UUID> alerted = new ArrayList<>();

    @Override
    public void providerBelowThreshold(UUID providerId, BigDecimal aggregateRating, BigDecimal threshold) {
        alerted.add(providerId);
    }
}
