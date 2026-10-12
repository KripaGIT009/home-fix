package com.homefix.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.auth.api.EmailAuthRequests.CodeSentResponse;
import com.homefix.auth.api.EmailAuthRequests.EmailOnlyRequest;
import com.homefix.auth.api.EmailAuthRequests.PasswordResetRequest;
import com.homefix.auth.emailauth.PasswordResetService;

/** Forgotten-password reset by emailed code (email-auth Requirement 3). Public. */
@RestController
@RequestMapping("/auth/password")
public class PasswordResetController {

    private final PasswordResetService resetService;

    public PasswordResetController(PasswordResetService resetService) {
        this.resetService = resetService;
    }

    /** {@code POST /auth/password/forgot} — 202 whether or not the address has an account. */
    @PostMapping("/forgot")
    public ResponseEntity<CodeSentResponse> forgot(@Valid @RequestBody EmailOnlyRequest request,
                                                   HttpServletRequest http) {
        long ttl = resetService.requestReset(request.email(), RequestContext.clientIp(http));
        return ResponseEntity.accepted().body(CodeSentResponse.of(ttl));
    }

    /** {@code POST /auth/password/reset} — 204; every session of the account is ended. */
    @PostMapping("/reset")
    public ResponseEntity<Void> reset(@Valid @RequestBody PasswordResetRequest request) {
        resetService.reset(request.email(), request.code(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
