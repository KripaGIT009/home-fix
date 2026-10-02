package com.homefix.rating.service;

/**
 * An Admin Portal moderation decision on a review (Requirement 19.2): PUBLISH approves it into the
 * aggregate (15.5), REMOVE deactivates it for a policy violation (15.9).
 */
public enum ModerationAction {
    PUBLISH,
    REMOVE
}
