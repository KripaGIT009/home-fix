package com.homefix.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

/**
 * {@link ServiceHost}: each service gets its own configuration name, port and prefixed
 * environment, the services run side by side in separate contexts, and a failure to start one
 * closes the others.
 */
class ServiceHostTest {

    private static final String[] NO_WEB = {"--spring.main.web-application-type=none"};

    @Test
    void argumentsAreTheDefaultsConfigNameAndPortThenTheJvmArguments() {
        HostedService booking = HostedService.of("booking-service", Object.class, 8084);

        assertThat(ServiceHost.argumentsFor(booking, new String[] {"--debug=false"})).containsExactly(
                "--spring.threads.virtual.enabled=true",
                "--spring.main.banner-mode=off",
                "--spring.jmx.enabled=false",
                "--spring.config.name=booking-service",
                "--server.port=8084",
                "--debug=false");
    }

    @Test
    void onlyThisServicesPrefixedVariablesAreTakenWithThePrefixRemoved() {
        HostedService booking = HostedService.of("booking-service", Object.class, 8084);
        Map<String, String> environment = Map.of(
                "BOOKING_SERVICE__DB_USERNAME", "booking_svc",
                "PAYMENT_SERVICE__DB_USERNAME", "payment_svc",
                "BOOKING_SERVICE__", "ignored",
                "DB_USERNAME", "shared");

        assertThat(ServiceHost.serviceVariables(booking, environment))
                .containsExactly(Map.entry("DB_USERNAME", "booking_svc"));
    }

    @Test
    void prefixedVariablesSupportRelaxedBindingAndOutrankTheSharedEnvironment() {
        HostedService pricing = HostedService.of("pricing-engine", Object.class, 8086);
        StandardEnvironment env = ServiceHost.environmentFor(pricing, Map.of(
                "PRICING_ENGINE__LOGGING_LEVEL_COM_HOMEFIX_SHARED_SECURITY", "DEBUG",
                "PRICING_ENGINE__PATH", "/only/pricing"));

        assertThat(env.getProperty("logging.level.com.homefix.shared.security")).isEqualTo("DEBUG");
        assertThat(env.getProperty("PATH")).isEqualTo("/only/pricing");
    }

    @Test
    void environmentPrefixIsTheUpperCasedNameWithUnderscores() {
        assertThat(HostedService.of("rating-review-service", Object.class, 8097).environmentPrefix())
                .isEqualTo("RATING_REVIEW_SERVICE__");
    }

    @Test
    void portMustBeValid() {
        assertThatThrownBy(() -> HostedService.of("x", Object.class, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runsEachServiceInItsOwnContext() {
        List<ConfigurableApplicationContext> contexts = ServiceHost.start(NO_WEB,
                Map.of("FIRST__GREETING", "hello", "SECOND__GREETING", "hi"),
                HostedService.of("first", GreetingApp.class, 18081),
                HostedService.of("second", GreetingApp.class, 18082));
        try {
            assertThat(contexts).hasSize(2);
            assertThat(contexts.get(0).getBean(Greeting.class).text()).isEqualTo("hello");
            assertThat(contexts.get(1).getBean(Greeting.class).text()).isEqualTo("hi");
            assertThat(contexts.get(0).getEnvironment().getProperty("server.port")).isEqualTo("18081");
            assertThat(contexts.get(1).getEnvironment().getProperty("spring.config.name")).isEqualTo("second");
        } finally {
            contexts.forEach(ConfigurableApplicationContext::close);
        }
    }

    @Test
    void aServiceThatFailsToStartClosesTheOnesAlreadyRunning() {
        assertThatThrownBy(() -> ServiceHost.start(NO_WEB,
                Map.of("FIRST__GREETING", "hello"),
                HostedService.of("first", GreetingApp.class, 18081),
                HostedService.of("broken", GreetingApp.class, 18082)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken");
    }

    record Greeting(String text) {
    }

    @SpringBootConfiguration
    static class GreetingApp {

        /** Fails to start when GREETING is not set (no auto-configuration, so no strict @Value). */
        @Bean
        Greeting greeting(Environment environment) {
            return new Greeting(environment.getRequiredProperty("GREETING"));
        }
    }
}
