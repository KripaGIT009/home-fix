package com.homefix.chat.config;

import jakarta.servlet.DispatcherType;

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
 * Spring Security configuration for the Chat Service.
 *
 * <p>Only the health and metrics surface is public. All other endpoints require an authenticated
 * principal; the participant-only 403 rule (Property 23) is enforced inside the service against the
 * channel's stored {@code (customerId, providerId)} rather than by a static role check. Without
 * this bean, Spring Boot's default security auto-configuration (pulled in transitively via the
 * shared-security module) would secure every endpoint with HTTP Basic.
 */
@Configuration
public class WebSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtValidationFilter jwtValidationFilter,
                                                   RbacEnforcementFilter rbacEnforcementFilter)
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
                        // Everything else requires an authenticated principal; participant-level
                        // access control is enforced by the ChatService (Property 23).
                        .anyRequest().authenticated())
                // The shared JWT and RBAC filters must run inside this chain: as plain
                // servlet filters they would execute before Spring Security installs its own
                // (anonymous) SecurityContext, which would discard the authenticated principal.
                .addFilterBefore(jwtValidationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rbacEnforcementFilter, JwtValidationFilter.class);
        return http.build();
    }
}
