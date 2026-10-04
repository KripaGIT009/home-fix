package com.homefix.auth.email;

/**
 * Outbound email (email-auth spec, Requirement 7). Every email the Auth Service sends (sign-up
 * codes, reset codes, invitations, agency decisions) goes through this port, so local runs need no
 * mail server and production plugs in a provider as one more adapter.
 *
 * <p>Selected by {@code homefix.email.provider}: {@code logging} (the default; records that a
 * message was accepted, never its body) or {@code file} (LOCAL DEV ONLY; appends whole messages,
 * codes and links included, to the Dev_Mail_Log). No production provider is built yet.
 */
public interface EmailSenderPort {

    /**
     * Sends one plain-text email.
     *
     * @param to      the recipient address
     * @param message subject and body
     * @throws EmailDeliveryException if the message could not be handed over for delivery
     */
    void send(String to, EmailMessage message) throws EmailDeliveryException;
}
