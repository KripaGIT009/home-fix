package com.homefix.shared.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Auto-configuration that wires the shared security components into any consuming
 * Spring Boot microservice simply by having this module on the classpath.
 *
 * <p>{@link CorrelationIdFilter} is registered as a plain servlet filter at
 * HIGHEST_PRECEDENCE so the correlation ID is set before anything logs.
 *
 * <p>{@link JwtValidationFilter} and {@link RbacEnforcementFilter} are exposed as ordinary
 * beans and are deliberately <em>not</em> registered with the servlet container: each service
 * adds them <em>inside</em> its {@code SecurityFilterChain} (see the per-service
 * {@code WebSecurityConfig}). Registering them as servlet filters would place them ahead of
 * Spring Security's own chain, whose {@code SecurityContextHolderFilter} then installs a fresh
 * (anonymous) {@code SecurityContext} and discards the JWT-derived authentication — every
 * authenticated endpoint would answer 403 even for a valid bearer token. The
 * {@link FilterRegistrationBean}s below therefore exist only to switch that automatic servlet
 * registration off.
 *
 * <p>The whole chain can be disabled with {@code homefix.security.enabled=false}.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "homefix.security", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({SecurityProperties.class, RbacProperties.class})
public class HomefixSecurityAutoConfiguration {

    private static final int CORRELATION_ID_ORDER = Ordered.HIGHEST_PRECEDENCE;

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter(CORRELATION_ID_ORDER));
        registration.setOrder(CORRELATION_ID_ORDER);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    @ConditionalOnProperty(prefix = "homefix.security", name = "jwt-secret")
    public JwtValidationFilter jwtValidationFilter(SecurityProperties properties) {
        return new JwtValidationFilter(properties.getJwtSecret());
    }

    /** Keeps the JWT filter out of the servlet chain; it runs inside the security chain. */
    @Bean
    @ConditionalOnProperty(prefix = "homefix.security", name = "jwt-secret")
    public FilterRegistrationBean<JwtValidationFilter> jwtValidationFilterRegistration(
            JwtValidationFilter jwtValidationFilter) {
        FilterRegistrationBean<JwtValidationFilter> registration =
                new FilterRegistrationBean<>(jwtValidationFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public RbacEnforcementFilter rbacEnforcementFilter(RbacProperties rbacProperties) {
        return new RbacEnforcementFilter(rbacProperties);
    }

    /** Keeps the RBAC filter out of the servlet chain; it runs inside the security chain. */
    @Bean
    public FilterRegistrationBean<RbacEnforcementFilter> rbacEnforcementFilterRegistration(
            RbacEnforcementFilter rbacEnforcementFilter) {
        FilterRegistrationBean<RbacEnforcementFilter> registration =
                new FilterRegistrationBean<>(rbacEnforcementFilter);
        registration.setEnabled(false);
        return registration;
    }
}
