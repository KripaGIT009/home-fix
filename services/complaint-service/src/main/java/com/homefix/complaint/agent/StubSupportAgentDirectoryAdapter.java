package com.homefix.complaint.agent;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Default {@link SupportAgentDirectoryPort} adapter. Deterministically synthesises an agent
 * identifier so the service is runnable end-to-end in dev; a production adapter would query the
 * staffing/roster service over HTTP or Kafka behind this same port without touching the complaint
 * logic. Activated only when no other {@link SupportAgentDirectoryPort} bean is present (tests
 * supply their own).
 */
@Component
public class StubSupportAgentDirectoryAdapter implements SupportAgentDirectoryPort {

    @Override
    public Optional<UUID> nextAvailableSupportAgent() {
        return Optional.of(UUID.randomUUID());
    }

    @Override
    public Optional<UUID> nextAvailableSeniorSupportAgent() {
        return Optional.of(UUID.randomUUID());
    }
}
