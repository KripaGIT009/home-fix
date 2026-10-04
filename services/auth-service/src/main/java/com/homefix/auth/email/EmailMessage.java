package com.homefix.auth.email;

/**
 * A plain-text email. Bodies may hold codes and invitation links, so they are never logged; only
 * the Dev_Mail_Log adapter writes them anywhere (Requirement 7.2).
 *
 * @param subject the subject line; safe to log
 * @param body    the text; never logged
 */
public record EmailMessage(String subject, String body) {
}
