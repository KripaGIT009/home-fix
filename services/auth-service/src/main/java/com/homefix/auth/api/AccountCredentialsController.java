package com.homefix.auth.api;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.auth.api.EmailAuthRequests.CodeOnlyRequest;
import com.homefix.auth.api.EmailAuthRequests.CodeSentResponse;
import com.homefix.auth.api.EmailAuthRequests.EmailChangeRequest;
import com.homefix.auth.api.EmailAuthRequests.PasswordChangeRequest;
import com.homefix.auth.emailauth.AccountCredentialsService;
import com.homefix.auth.emailauth.AccountCredentialsService.Credentials;

/**
 * The signed-in person's own email and password (email-auth Requirement 4), for every role: the
 * account is always the token's subject, never a path parameter, so nobody can act on another
 * account here.
 */
@RestController
@RequestMapping("/auth/me")
public class AccountCredentialsController {

    private final AccountCredentialsService credentialsService;

    public AccountCredentialsController(AccountCredentialsService credentialsService) {
        this.credentialsService = credentialsService;
    }

    /** {@code GET /auth/me}. */
    @GetMapping
    public Credentials me() {
        return credentialsService.credentials(RequestContext.currentActor().userId());
    }

    /** {@code POST /auth/me/email} — email a code to the new address (202). */
    @PostMapping("/email")
    public ResponseEntity<CodeSentResponse> requestEmailChange(@Valid @RequestBody EmailChangeRequest request) {
        long ttl = credentialsService.requestEmailChange(RequestContext.currentActor().userId(),
                request.email(), request.currentPassword());
        return ResponseEntity.accepted().body(CodeSentResponse.of(ttl));
    }

    /** {@code POST /auth/me/email/verify} — save the address once its code is entered. */
    @PostMapping("/email/verify")
    public Credentials confirmEmailChange(@Valid @RequestBody CodeOnlyRequest request) {
        return credentialsService.confirmEmailChange(RequestContext.currentActor().userId(), request.code());
    }

    /** {@code PUT /auth/me/password} — set or change the password (204). */
    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChangeRequest request) {
        credentialsService.changePassword(RequestContext.currentActor().userId(), request.currentPassword(),
                request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
