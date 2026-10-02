package com.homefix.dispatch.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the authenticated caller for the provider-facing offer endpoints.
 *
 * <p>The shared {@code RbacEnforcementFilter} only answers "is this caller a SERVICE_PROVIDER?"
 * (see {@code DispatchRbacConfig}). Which offers they may see and decide is a per-caller question:
 * an offer belongs to the provider it was made to, identified by the JWT subject. Dispatch
 * candidates carry that same id, because provider-service pins a provider profile's id to its
 * owner's auth user id and reports it as the eligible provider's {@code providerId}.
 */
@Component
public class CallerIdentity {

    /**
     * @return the authenticated caller's user id (the JWT subject)
     * @throws OfferApiException 401 when unauthenticated or the principal name is not a UUID
     */
    public UUID requireCallerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new OfferApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "no authenticated principal");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new OfferApiException(HttpStatus.UNAUTHORIZED, "INVALID_PRINCIPAL",
                    "authenticated principal is not a valid user ID");
        }
    }
}
