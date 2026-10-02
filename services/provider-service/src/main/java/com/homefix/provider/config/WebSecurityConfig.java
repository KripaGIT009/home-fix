package com.homefix.provider.config;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.homefix.shared.security.JwtValidationFilter;
import com.homefix.shared.security.RbacEnforcementFilter;

/**
 * Spring Security configuration for the Provider Service.
 *
 * <p>Only the health and metrics surface is public. Provider-facing endpoints require an
 * authenticated principal; fine-grained role checks are performed by the shared
 * {@code RbacEnforcementFilter} (Task 4). Without this bean, Spring Boot's default security
 * auto-configuration (pulled in transitively via the shared-security module) would secure
 * every endpoint with HTTP Basic.
 *
 * <p>{@code /internal/**} is the service-to-service surface (the Dispatch Engine's eligible-provider
 * search). It is guarded by {@link InternalApiKeyFilter} — the same shared-credential mechanism the
 * Booking and Customer Services use — and additionally requires the {@code ROLE_INTERNAL} authority
 * that only that filter grants, so an end-user JWT can never reach it even if the filter's prefix
 * check were somehow bypassed. No RBAC rule covers it (see {@code ProviderRbacConfig}), and the API
 * Gateway routes only {@code /providers/**} here.
 */
@Configuration
public class WebSecurityConfig {

    /**
     * Guards {@code /internal/**} with the shared service credential. Registered as a bean so the
     * key comes from configuration and the filter is unit-testable on its own.
     */
    @Bean
    public InternalApiKeyFilter internalApiKeyFilter(
            @Value("${homefix.provider.internal-api-key:}") String internalApiKey) {
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
