package com.homefix.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class HomefixSecurityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HomefixSecurityAutoConfiguration.class));

    @Test
    void registersCorrelationAndRbacFilters_byDefault() {
        runner.run(context -> {
            assertThat(context).hasBean("correlationIdFilterRegistration");
            assertThat(context).hasSingleBean(RbacEnforcementFilter.class);
            // No jwt-secret configured -> jwt filter bean absent.
            assertThat(context).doesNotHaveBean(JwtValidationFilter.class);
            assertThat(context).hasSingleBean(SecurityProperties.class);
            assertThat(context).hasSingleBean(RbacProperties.class);
        });
    }

    @Test
    void registersJwtFilter_whenSecretProvided() {
        runner.withPropertyValues(
                        "homefix.security.jwt-secret=homefix-test-signing-secret-key-0123456789")
                .run(context -> {
                    assertThat(context).hasSingleBean(JwtValidationFilter.class);
                    SecurityProperties props = context.getBean(SecurityProperties.class);
                    assertThat(props.getJwtSecret()).isNotBlank();
                    assertThat(props.isEnabled()).isTrue();
                });
    }

    /**
     * The JWT and RBAC filters run inside each service's Spring Security chain, so their
     * automatic servlet-container registration must stay switched off — otherwise they would
     * execute ahead of Spring Security, which then replaces the SecurityContext they populated.
     */
    @Test
    void jwtAndRbacFiltersAreNotRegisteredWithTheServletContainer() {
        runner.withPropertyValues(
                        "homefix.security.jwt-secret=homefix-test-signing-secret-key-0123456789")
                .run(context -> {
                    assertThat(context.getBean("jwtValidationFilterRegistration",
                            FilterRegistrationBean.class).isEnabled()).isFalse();
                    assertThat(context.getBean("rbacEnforcementFilterRegistration",
                            FilterRegistrationBean.class).isEnabled()).isFalse();
                    assertThat(context.getBean("correlationIdFilterRegistration",
                            FilterRegistrationBean.class).isEnabled()).isTrue();
                });
    }

    @Test
    void disabled_registersNoFilters() {
        runner.withPropertyValues("homefix.security.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("correlationIdFilterRegistration");
                    assertThat(context).doesNotHaveBean(RbacEnforcementFilter.class);
                    assertThat(context).doesNotHaveBean(JwtValidationFilter.class);
                });
    }

    @Test
    void rbacPropertiesBeanIsAvailableAndMutable() {
        // Spring's relaxed binder cannot bind map keys that contain spaces/slashes
        // (e.g. "GET /admin/**") from flat property strings, so services populate
        // the map programmatically or via YAML. Here we verify the bean is exposed
        // and behaves as a normal mutable holder.
        runner.run(context -> {
            RbacProperties props = context.getBean(RbacProperties.class);
            assertThat(props.getEndpointRoles()).isEmpty();
            props.getEndpointRoles().put("GET /admin/**", java.util.List.of("ADMIN"));
            assertThat(props.getEndpointRoles()).containsEntry("GET /admin/**", java.util.List.of("ADMIN"));
        });
    }
}
