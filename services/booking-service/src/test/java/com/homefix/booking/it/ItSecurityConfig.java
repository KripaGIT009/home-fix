package com.homefix.booking.it;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.homefix.shared.security.JwtValidationFilter;
import com.homefix.shared.security.RbacEnforcementFilter;
import com.homefix.shared.security.RbacProperties;
import com.homefix.shared.security.SecurityProperties;

/**
 * Integration-test security wiring ({@code it} profile only).
 *
 * <p>The shared security auto-configuration registers the JWT and RBAC filters as servlet
 * {@code FilterRegistrationBean}s that run <em>before</em> Spring Security's own filter chain.
 * Spring Security then installs a fresh (anonymous) {@code SecurityContext} at the start of its
 * chain, which discards the authentication those servlet filters set — so by the time the
 * controller reads the {@code Authentication} it is anonymous and {@code .authenticated()}
 * denies with 403.
 *
 * <p>For the end-to-end HTTP tests we therefore run the same shared {@link JwtValidationFilter}
 * and {@link RbacEnforcementFilter} <em>inside</em> the Spring Security chain (before the
 * username/password filter), so the JWT-derived principal survives into the controller and the
 * real authentication + role enforcement is still exercised end-to-end. This mirrors what the
 * production gateway/service chain achieves; it only changes where the shared filters run, not
 * what they do.
 */
@Configuration
@Profile("it")
@EnableConfigurationProperties({SecurityProperties.class, RbacProperties.class})
class ItSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain itSecurityFilterChain(HttpSecurity http,
                                              SecurityProperties securityProperties,
                                              RbacProperties rbacProperties) throws Exception {
        JwtValidationFilter jwtFilter = new JwtValidationFilter(securityProperties.getJwtSecret());
        RbacEnforcementFilter rbacFilter = new RbacEnforcementFilter(rbacProperties);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health/**", "/actuator/**", "/metrics", "/prometheus").permitAll()
                        .anyRequest().authenticated())
                // Run the shared JWT + RBAC filters within the chain so the authenticated
                // principal established from the bearer token reaches the controller.
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rbacFilter, JwtValidationFilter.class);

        return http.build();
    }
}
