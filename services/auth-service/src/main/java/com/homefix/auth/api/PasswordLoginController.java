package com.homefix.auth.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.auth.password.PasswordLoginService;
import com.homefix.auth.password.PasswordLoginService.LoginResult;

import jakarta.validation.Valid;

/**
 * Username and password sign-in (the staff console path).
 *
 * <p>Sits beside {@code /auth/login/social} rather than under {@code /auth/register}, because
 * unlike OTP verification this endpoint never creates an account. It authenticates one that
 * already carries credentials and returns the same {@link TokenResponse} body every other
 * authentication path returns, so clients handle one shape.
 */
@RestController
@RequestMapping("/auth/login")
public class PasswordLoginController {

    private final PasswordLoginService passwordLoginService;

    public PasswordLoginController(PasswordLoginService passwordLoginService) {
        this.passwordLoginService = passwordLoginService;
    }

    /**
     * {@code POST /auth/login/password} — authenticate with an email or username and a password.
     *
     * <p>200 rather than 201: no account is created here.
     */
    @PostMapping("/password")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody PasswordLoginRequest request) {
        LoginResult result = passwordLoginService.authenticate(request.identifier(), request.password());
        return ResponseEntity.ok(
                TokenResponse.from(result.userId(), result.roles(), result.tokens()));
    }
}
