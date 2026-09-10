package com.homefix.complaint.agent;

import java.util.Optional;
import java.util.UUID;

/**
 * Supplies available support agents for complaint assignment and escalation (Requirement 16.1,
 * 16.4). Modelled as a port so the source of agent availability (a staffing service, a database
 * roster, etc.) can vary without touching the complaint logic, and so it is mockable in unit tests.
 */
public interface SupportAgentDirectoryPort {

    /** An available Support_Agent to assign a new complaint to (Requirement 16.1), if any. */
    Optional<UUID> nextAvailableSupportAgent();

    /** An available Senior_Support_Agent to escalate a breached complaint to (16.4), if any. */
    Optional<UUID> nextAvailableSeniorSupportAgent();
}
