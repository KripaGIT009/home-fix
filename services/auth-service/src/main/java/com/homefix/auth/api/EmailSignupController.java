package com.homefix.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.auth.api.EmailAuthRequests.CodeSentResponse;
import com.homefix.auth.api.EmailAuthRequests.EmailCodeRequest;
import com.homefix.auth.api.EmailAuthRequests.EmailOnlyRequest;
import com.homefix.auth.api.EmailAuthRequests.EmailSignupRequest;
import com.homefix.auth.emailauth.EmailSignupService;
import com.homefix.auth.emailauth.EmailSignupService.SignupCommand;
import com.homefix.auth.password.PasswordLoginService.LoginResult;

/**
 * Email sign-up for the customer and provider apps (email-auth Requirement 1). Public: under
 * {@code /auth/register/**}, which needs no token.
 */
@RestController
@RequestMapping("/auth/register/email")
public class EmailSignupController {

    private final EmailSignupService signupService;

    public EmailSignupController(EmailSignupService signupService) {
        this.signupService = signupService;
    }

    /**
     * {@code POST /auth/register/email} — start a sign-up; the code goes to the address. 202 in every
     * case an address could be told apart by (Property EA1).
     */
    @PostMapping
    public ResponseEntity<CodeSentResponse> register(@Valid @RequestBody EmailSignupRequest request,
                                                     HttpServletRequest http) {
        long ttl = signupService.register(new SignupCommand(request.displayName(), request.email(),
                request.mobileNumber(), request.password(), request.role()), RequestContext.clientIp(http));
        return ResponseEntity.accepted().body(CodeSentResponse.of(ttl));
    }

    /** {@code POST /auth/register/email/verify} — enter the code; signed in on success. */
    @PostMapping("/verify")
    public ResponseEntity<TokenResponse> verify(@Valid @RequestBody EmailCodeRequest request) {
        LoginResult result = signupService.verify(request.email(), request.code());
        return ResponseEntity.ok(TokenResponse.from(result.userId(), result.roles(), result.tokens()));
    }

    /** {@code POST /auth/register/email/resend} — a fresh code for a sign-up still waiting. */
    @PostMapping("/resend")
    public ResponseEntity<CodeSentResponse> resend(@Valid @RequestBody EmailOnlyRequest request,
                                                   HttpServletRequest http) {
        long ttl = signupService.resend(request.email(), RequestContext.clientIp(http));
        return ResponseEntity.accepted().body(CodeSentResponse.of(ttl));
    }
}
