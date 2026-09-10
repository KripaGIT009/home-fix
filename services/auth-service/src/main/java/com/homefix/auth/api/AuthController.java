package com.homefix.auth.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.auth.registration.RegistrationService;
import com.homefix.auth.registration.RegistrationService.VerificationResult;

import jakarta.validation.Valid;

/**
 * OTP registration endpoints (Requirement 1).
 */
@RestController
@RequestMapping("/auth/register")
public class AuthController {

    private final RegistrationService registrationService;

    public AuthController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    /**
     * {@code POST /auth/register/otp} — send an OTP to a mobile number.
     */
    @PostMapping("/otp")
    public ResponseEntity<OtpResponse> requestOtp(@Valid @RequestBody OtpRequest request) {
        long expiresIn = registrationService.requestOtp(request.mobileNumber(), request.role());
        return ResponseEntity.accepted().body(OtpResponse.sent(expiresIn));
    }

    /**
     * {@code POST /auth/register/verify} — verify an OTP, create the account, return tokens.
     */
    @PostMapping("/verify")
    public ResponseEntity<TokenResponse> verifyOtp(@Valid @RequestBody VerifyRequest request) {
        VerificationResult result = registrationService.verifyOtp(request.mobileNumber(), request.otp());
        TokenResponse body = TokenResponse.from(result.userId(), result.roles(), result.tokens());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
