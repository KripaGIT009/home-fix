package com.homefix.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OncePerRequestFilter that enforces role-based access control (RBAC) for each
 * incoming request.
 *
 * <p>The filter evaluates a per-endpoint allow-list sourced from
 * {@link RbacProperties}.  Each entry maps a pattern {@code "METHOD /path/**"} to a
 * list of roles; if the authenticated principal holds <em>any</em> of the listed roles
 * the request is permitted.
 *
 * <p>Decision logic:
 * <ol>
 *   <li>If no pattern matches the current request, the filter passes through
 *       (endpoint is not RBAC-protected).</li>
 *   <li>If a pattern matches but the principal is unauthenticated, returns 401.</li>
 *   <li>If the principal is authenticated but holds none of the required roles,
 *       returns 403.</li>
 *   <li>If the principal holds at least one of the required roles, the request
 *       continues down the filter chain.</li>
 * </ol>
 *
 * <p>This filter must be registered <em>after</em> {@link JwtValidationFilter} so
 * that the {@link SecurityContextHolder} is already populated.
 */
public class RbacEnforcementFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RbacEnforcementFilter.class);

    private final RbacProperties rbacProperties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public RbacEnforcementFilter(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String method = request.getMethod();
        String path = request.getRequestURI();

        List<String> requiredRoles = findRequiredRoles(method, path);

        if (requiredRoles == null) {
            // No RBAC rule for this endpoint — pass through
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("Unauthenticated request to RBAC-protected endpoint {} {}", method, path);
            sendError(response, HttpStatus.UNAUTHORIZED, "Authentication required");
            return;
        }

        Set<String> principalRoles = extractPrincipalRoles(authentication.getAuthorities());

        boolean hasRequiredRole = requiredRoles.stream()
                .anyMatch(required -> principalRoles.contains("ROLE_" + required));

        if (!hasRequiredRole) {
            log.warn("Access denied for principal '{}' to {} {}. Required roles: {}, Principal roles: {}",
                    authentication.getName(), method, path, requiredRoles, principalRoles);
            sendError(response, HttpStatus.FORBIDDEN, "Insufficient role");
            return;
        }

        filterChain.doFilter(request, response);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the list of allowed roles for the first matching endpoint pattern,
     * or {@code null} if no pattern matches (endpoint is unprotected).
     */
    private List<String> findRequiredRoles(String method, String path) {
        for (Map.Entry<String, List<String>> entry : rbacProperties.getEndpointRoles().entrySet()) {
            String pattern = entry.getKey();
            if (matches(pattern, method, path)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Matches a pattern of the form {@code "METHOD /path/**"} or just {@code "/path/**"}
     * against the incoming method and path.
     */
    private boolean matches(String pattern, String method, String path) {
        int spaceIdx = pattern.indexOf(' ');
        if (spaceIdx > 0) {
            String patternMethod = pattern.substring(0, spaceIdx).toUpperCase();
            String patternPath = pattern.substring(spaceIdx + 1);
            return patternMethod.equals(method.toUpperCase())
                    && pathMatcher.match(patternPath, path);
        }
        // No method prefix — match path only
        return pathMatcher.match(pattern, path);
    }

    private Set<String> extractPrincipalRoles(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    private void sendError(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
