package com.homefix.host;

import java.util.Locale;
import java.util.Objects;

/**
 * One service run by a {@link ServiceHost}.
 *
 * @param name        the service's name, e.g. {@code booking-service}. Its configuration file is
 *                    {@code <name>.yml} on the classpath, and environment variables prefixed with
 *                    {@code <NAME>__} (upper case, hyphens as underscores) apply to it alone
 * @param application the service's {@code @SpringBootApplication} class
 * @param port        the HTTP port it listens on
 */
public record HostedService(String name, Class<?> application, int port) {

    public HostedService {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(application, "application must not be null");
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }

    public static HostedService of(String name, Class<?> application, int port) {
        return new HostedService(name, application, port);
    }

    /** Prefix of the environment variables that apply to this service only. */
    public String environmentPrefix() {
        return name.toUpperCase(Locale.ROOT).replace('-', '_') + "__";
    }
}
