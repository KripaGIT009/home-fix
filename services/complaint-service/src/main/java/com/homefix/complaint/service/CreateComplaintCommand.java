package com.homefix.complaint.service;

import java.util.List;
import java.util.UUID;

import com.homefix.complaint.domain.ComplaintCategory;
import com.homefix.complaint.domain.ServicePriority;

/**
 * Command carrying the data needed to create a complaint (Requirement 16.1).
 *
 * @param bookingId   the completed booking the complaint is about
 * @param customerId  the authenticated customer raising the complaint
 * @param providerId  the provider on the booking (whose settlement may later be held, 16.7)
 * @param category    one of the seven complaint categories (16.1)
 * @param priority    booking priority selecting the resolution SLA (16.4)
 * @param description free-text description, at most 2000 characters (16.1)
 * @param attachments up to 5 evidence attachments, each at most 10 MB (16.1)
 */
public record CreateComplaintCommand(
        UUID bookingId,
        UUID customerId,
        UUID providerId,
        ComplaintCategory category,
        ServicePriority priority,
        String description,
        List<AttachmentMetadata> attachments) {
}
