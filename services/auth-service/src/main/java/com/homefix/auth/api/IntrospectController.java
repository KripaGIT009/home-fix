package com.homefix.auth.api;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.auth.token.TokenService;

/**
 * {@code GET /auth/introspect} — validates a JWT and returns its claims for the API Gateway
 * (design: "used by API Gateway").
 *
 * <p>The token can be supplied either as a bearer {@code Authorization} header or a
 * {@code token} query parameter. The response mirrors the OAuth2 token-introspection shape:
 * {@code {active:true|false, ...}}. Introspection always returns HTTP 200; the {@code active}
 * flag conveys validity so gateways can branch without handling error status codes.
 */
@RestController
public class IntrospectController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;

    public IntrospectController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @GetMapping("/auth/introspect")
    public ResponseEntity<Map<String, Object>> introspect(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(value = "token", required = false) String tokenParam) {

        String token = resolveToken(authorization, tokenParam);
        return ResponseEntity.ok(tokenService.introspect(token));
    }

    private String resolveToken(String authorization, String tokenParam) {
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return tokenParam;
    }
}
