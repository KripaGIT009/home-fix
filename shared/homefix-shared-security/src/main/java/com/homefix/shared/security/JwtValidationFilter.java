package com.homefix.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * OncePerRequestFilter that validates an incoming JWT bearer token.
 *
 * <ul>
 *   <li>Extracts the token from the {@code Authorization: Bearer <token>} header.</li>
 *   <li>Verifies the HMAC-SHA256 signature using the configured secret key.</li>
 *   <li>Rejects expired tokens with HTTP 401.</li>
 *   <li>Rejects malformed / invalid-signature tokens with HTTP 401.</li>
 *   <li>On success, populates the {@link SecurityContextHolder} with an
 *       {@link UsernamePasswordAuthenticationToken} carrying the subject and role claims.</li>
 * </ul>
 *
 * Downstream filters (e.g. {@link RbacEnforcementFilter}) rely on the populated
 * {@link SecurityContextHolder} to perform role-based access control.
 */
public class JwtValidationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtValidationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    /** JWT claim key that carries the list of role strings, e.g. ["CUSTOMER", "SERVICE_PROVIDER"]. */
    public static final String ROLES_CLAIM = "roles";

    private final SecretKey signingKey;

    /**
     * @param jwtSecret raw HMAC secret; must be at least 256 bits (32 bytes) for HS256.
     */
    public JwtValidationFilter(String jwtSecret) {
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Package-visible constructor accepting a pre-built key (used in tests).
     */
    JwtValidationFilter(SecretKey signingKey) {
        this.signingKey = signingKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);

        if (token == null) {
            // No bearer token — let downstream security config decide (e.g. permit public endpoints)
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String subject = claims.getSubject();
            List<String> roles = extractRoles(claims);

            List<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .toList();

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(subject, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (ExpiredJwtException ex) {
            log.warn("JWT expired for request {}: {}", request.getRequestURI(), ex.getMessage());
            sendError(response, HttpStatus.UNAUTHORIZED, "Token has expired");
            return;
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("Invalid JWT for request {}: {}", request.getRequestURI(), ex.getMessage());
            sendError(response, HttpStatus.UNAUTHORIZED, "Invalid token");
            return;
        }

        filterChain.doFilter(request, response);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRoles(Claims claims) {
        Object rolesClaim = claims.get(ROLES_CLAIM);
        if (rolesClaim instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return Collections.emptyList();
    }

    private void sendError(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
