package com.homefix.notification.config;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.homefix.shared.security.JwtValidationFilter;
import com.homefix.shared.security.RbacEnforcementFilter;

/**
 * Spring Security configuration for the Notification Service.
 *
 * <p>Only the health and metrics surface is public. All other endpoints require an authenticated
 * principal; fine-grained role checks are performed by the shared {@code RbacEnforcementFilter}
 * (Task 4) against the allow-list in {@link NotificationRbacConfig} (the Admin Portal's
 * {@code /admin/notification-templates/**}: ADMIN, SUPER_ADMIN). Without this bean, Spring Boot's default security auto-configuration (pulled in
 * transitively via the shared-security module) would secure every endpoint with HTTP Basic.
 *
 * <p>Inactive under the {@code it} profile, as in the Booking Service: the Kafka integration test
 * disables the shared security filters ({@code homefix.security.enabled=false}) because it drives
 * only the consumer path, and this chain cannot be built without them.
 */
@Configuration
@Profile("!it")
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
                        // Everything else requires an authenticated principal; fine-grained
                        // role checks are performed by the shared RbacEnforcementFilter.
                        .anyRequest().authenticated())
                // The shared JWT and RBAC filters must run inside this chain: as plain
                // servlet filters they would execute before Spring Security installs its own
                // (anonymous) SecurityContext, which would discard the authenticated principal.
                .addFilterBefore(jwtValidationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rbacEnforcementFilter, JwtValidationFilter.class);
        return http.build();
    }
}
