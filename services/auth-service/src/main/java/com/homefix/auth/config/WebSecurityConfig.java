package com.homefix.auth.config;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.homefix.shared.security.JwtValidationFilter;
import com.homefix.shared.security.RbacEnforcementFilter;

/**
 * Spring Security configuration for the Auth Service.
 *
 * <p>The registration and introspection endpoints are public by design: registration is how
 * an unauthenticated user first obtains a token, and introspection is called by the API
 * Gateway itself. Per-endpoint role enforcement for authenticated endpoints is handled by
 * the shared {@code RbacEnforcementFilter} (Task 4), over the rules in {@link AuthRbacConfig}
 * (the Admin Portal's {@code /admin/users/**}), so the Spring Security chain here only needs to
 * permit the public surface and remain stateless.
 *
 * <p>{@code /internal/**} is the service-to-service surface (Notification Service contact
 * lookup; provider-service account lookup and TENANT_ADMIN grant and revoke). It is guarded by {@link InternalApiKeyFilter} — the same shared-credential mechanism
 * the Booking Service uses — and additionally requires the {@code ROLE_INTERNAL} authority that
 * only that filter grants, so an end-user JWT can never reach it even if the filter's prefix
 * check were somehow bypassed. The API Gateway has no route for {@code /internal/**}.
 *
 * <p>Without this bean, Spring Boot's default security auto-configuration (pulled in
 * transitively via the shared-security module) would secure every endpoint with HTTP Basic,
 * blocking the public registration flow.
 */
@Configuration
@EnableWebSecurity
public class WebSecurityConfig {

    /**
     * Guards {@code /internal/**} with the shared service credential. Registered as a bean so the
     * key comes from configuration and the filter is unit-testable on its own.
     */
    @Bean
    public InternalApiKeyFilter internalApiKeyFilter(
            @Value("${homefix.auth.internal-api-key:}") String internalApiKey) {
        return new InternalApiKeyFilter(internalApiKey);
    }

    /**
     * Keeps the internal-API filter out of the plain servlet chain; it runs inside the security
     * chain below, for the same reason the shared JWT and RBAC filters do.
     */
    @Bean
    public FilterRegistrationBean<InternalApiKeyFilter> internalApiKeyFilterRegistration(
            InternalApiKeyFilter filter) {
        FilterRegistrationBean<InternalApiKeyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtValidationFilter jwtValidationFilter,
                                                   RbacEnforcementFilter rbacEnforcementFilter,
                                                   InternalApiKeyFilter internalApiKeyFilter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Boot's default chain permits the ERROR dispatch; a custom chain must do
                        // the same. Without it Spring MVC's forward to /error is re-evaluated as an
                        // unauthenticated request, so every application 404/400/500 reaches the
                        // client as an empty 403 and the real status is lost.
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC)
                        .permitAll()
                        .requestMatchers(
                                "/auth/register/**",
                                "/auth/login/social",
                                "/auth/login/password",
                                "/auth/token/refresh",
                                "/auth/logout",
                                "/auth/introspect",
                                "/health/**",
                                "/actuator/**",
                                "/metrics",
                                "/prometheus")
                        .permitAll()
                        // Service-to-service only: the authority is granted solely by the
                        // internal-API filter, never by a user token.
                        .requestMatchers("/internal/**")
                        .hasAuthority(InternalApiKeyFilter.INTERNAL_AUTHORITY)
                        // Everything else requires an authenticated principal; fine-grained
                        // role checks are performed by the shared RbacEnforcementFilter.
                        .anyRequest().authenticated())
                // The shared JWT and RBAC filters must run inside this chain: as plain
                // servlet filters they would execute before Spring Security installs its own
                // (anonymous) SecurityContext, which would discard the authenticated principal.
                // The internal-API filter runs first and only touches /internal/**.
                .addFilterBefore(internalApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(jwtValidationFilter, InternalApiKeyFilter.class)
                .addFilterAfter(rbacEnforcementFilter, JwtValidationFilter.class);
        return http.build();
    }
}
