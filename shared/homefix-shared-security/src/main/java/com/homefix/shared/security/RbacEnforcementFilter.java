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
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
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
 *   <li>If the request path is ambiguous — anything Spring Security's {@link StrictHttpFirewall}
 *       refuses, or a malformed percent-encoding — returns 400 without evaluating any rule.</li>
 *   <li>If no pattern matches the current request, the filter passes through
 *       (endpoint is not RBAC-protected).</li>
 *   <li>If a pattern matches but the principal is unauthenticated, returns 401.</li>
 *   <li>If the principal is authenticated but holds none of the required roles,
 *       returns 403.</li>
 *   <li>If the principal holds at least one of the required roles, the request
 *       continues down the filter chain.</li>
 * </ol>
 *
 * <h2>Which path is matched</h2>
 * <p>Rules are matched against the path the handler mapping resolves, not the raw
 * {@code getRequestURI()}. Matching the raw URI let a request reach a protected handler without
 * matching its rule, and because an unmatched request passes through, that was a role bypass:
 * {@code /admin;x/users}, {@code /%61dmin/users}, {@code /ctx/admin/users} under a servlet context
 * path, and {@code HEAD /admin/users} (Spring MVC serves HEAD from the GET handler) all reached the
 * {@code GET /admin/**} handler unchecked. The path is now derived in two steps:
 * <ol>
 *   <li><strong>Refuse anything that can be read more than one way.</strong> The request is run
 *       through a {@link StrictHttpFirewall} with its default settings — the same check
 *       {@code FilterChainProxy} applies before this filter runs. Path parameters ({@code ;}),
 *       encoded {@code /}, {@code \}, {@code .} and {@code %}, empty ({@code //}), {@code .} and
 *       {@code ..} segments, and non-printable characters are refused with 400. Re-checking here
 *       keeps the filter safe on its own (it is a public class, and tests or future callers may
 *       invoke it outside a {@code FilterChainProxy}), and it guarantees the decoding in the next
 *       step cannot create a new {@code /} or {@code ..} segment.</li>
 *   <li><strong>Normalise what remains.</strong> The path within the application is taken from
 *       {@link UrlPathHelper#getPathWithinApplication}: percent-decoded and stripped of the servlet
 *       context path, which is how Spring MVC sees it. A single trailing {@code /} is then removed,
 *       so {@code /bookings/} is governed by the {@code /bookings} rule even if a service ever
 *       enables trailing-slash matching. (Two or more trailing slashes are an empty segment and
 *       were already refused.)</li>
 * </ol>
 * Pattern matching is case-insensitive, and a {@code GET} rule also governs {@code HEAD}. Both only
 * ever make a rule match <em>more</em> requests, never fewer, so neither can open a path: the first
 * closes the gap should a service ever enable case-insensitive MVC matching, the second closes the
 * HEAD-to-GET fallback Spring MVC already performs.
 *
 * <p>The pass-through-when-unmatched semantics is kept deliberately: public paths are left unruled
 * because a rule inside the security chain would turn them into a 401. With the path normalised as
 * above, an unmatched request is one whose handler path genuinely has no rule, and it is still
 * subject to the service's own {@code authorizeHttpRequests} configuration.
 *
 * <p>This filter must be registered <em>after</em> {@link JwtValidationFilter} so
 * that the {@link SecurityContextHolder} is already populated.
 */
public class RbacEnforcementFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RbacEnforcementFilter.class);

    private static final String GET = "GET";
    private static final String HEAD = "HEAD";

    private final RbacProperties rbacProperties;
    private final AntPathMatcher pathMatcher;
    private final HttpFirewall firewall;
    private final UrlPathHelper urlPathHelper;

    public RbacEnforcementFilter(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
        this.pathMatcher = new AntPathMatcher();
        this.pathMatcher.setCaseSensitive(false);
        this.firewall = new StrictHttpFirewall();
        this.urlPathHelper = new UrlPathHelper();
        this.urlPathHelper.setUrlDecode(true);
        this.urlPathHelper.setRemoveSemicolonContent(true);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String method = request.getMethod();
        String path;
        try {
            path = resolveLookupPath(request);
        } catch (RequestRejectedException | IllegalArgumentException ex) {
            // The same refusal FilterChainProxy gives: never evaluate rules against an ambiguous path.
            log.warn("Rejected request with an ambiguous path before RBAC evaluation: {}",
                    ex.getMessage());
            sendError(response, HttpStatus.BAD_REQUEST, "Malformed request path");
            return;
        }

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
     * Returns the normalised path within the application that rules are matched against (see
     * the class Javadoc).
     *
     * @throws RequestRejectedException if the request is one {@link StrictHttpFirewall} refuses
     * @throws IllegalArgumentException if the path holds a malformed percent-encoding
     */
    private String resolveLookupPath(HttpServletRequest request) {
        // Validation only: the firewalled wrapper is discarded and the original request continues
        // down the chain, so nothing downstream sees a different request object.
        firewall.getFirewalledRequest(request);
        String path = urlPathHelper.getPathWithinApplication(request);
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

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
     * against the incoming method and normalised path. A {@code GET} pattern also matches
     * {@code HEAD}, because Spring MVC answers HEAD by invoking the GET handler.
     */
    private boolean matches(String pattern, String method, String path) {
        int spaceIdx = pattern.indexOf(' ');
        if (spaceIdx > 0) {
            String patternMethod = pattern.substring(0, spaceIdx).toUpperCase(Locale.ROOT);
            String patternPath = pattern.substring(spaceIdx + 1);
            String requestMethod = method.toUpperCase(Locale.ROOT);
            boolean methodMatches = patternMethod.equals(requestMethod)
                    || (HEAD.equals(requestMethod) && GET.equals(patternMethod));
            return methodMatches && pathMatcher.match(patternPath, path);
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
