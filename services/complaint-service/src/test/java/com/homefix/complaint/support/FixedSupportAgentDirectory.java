package com.homefix.complaint.support;

import java.util.Optional;
import java.util.UUID;

import com.homefix.complaint.agent.SupportAgentDirectoryPort;

/**
 * Deterministic {@link SupportAgentDirectoryPort} test double. Returns preconfigured agent IDs so
 * assignment (16.1) and escalation (16.4) can be asserted, and can simulate "no agent available".
 */
public class FixedSupportAgentDirectory implements SupportAgentDirectoryPort {

    private UUID supportAgent;
    private UUID seniorAgent;

    public FixedSupportAgentDirectory(UUID supportAgent, UUID seniorAgent) {
        this.supportAgent = supportAgent;
        this.seniorAgent = seniorAgent;
    }

    public void setSupportAgent(UUID supportAgent) {
        this.supportAgent = supportAgent;
    }

    public void setSeniorAgent(UUID seniorAgent) {
        this.seniorAgent = seniorAgent;
    }

    @Override
    public Optional<UUID> nextAvailableSupportAgent() {
        return Optional.ofNullable(supportAgent);
    }

    @Override
    public Optional<UUID> nextAvailableSeniorSupportAgent() {
        return Optional.ofNullable(seniorAgent);
    }
}
