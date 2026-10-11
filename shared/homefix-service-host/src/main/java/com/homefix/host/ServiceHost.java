package com.homefix.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs several HomeFix services in one JVM. Each service starts as its own Spring Boot
 * application — its own context, configuration, beans, security filter chain, database schema,
 * Flyway history, Kafka listeners and embedded web server on its own port — exactly as it ran
 * when it had a JVM to itself. What the services now share is the JVM: one heap, one copy of every
 * class, one JIT and one garbage collector, which is where most of a small Spring service's
 * memory goes.
 *
 * <p>Each service is configured, from lowest to highest precedence, by:
 * <ul>
 *   <li>its {@code <name>.yml} on the classpath ({@code spring.config.name}), so the services'
 *       configuration files do not collide on the shared classpath;</li>
 *   <li>the JVM's environment variables, as before;</li>
 *   <li>the environment variables {@code <NAME>__VAR}, seen by this service alone as {@code VAR}:
 *       a value that must differ between services in one JVM (a database user, a log level) is
 *       set per service, e.g. {@code BOOKING_SERVICE__DB_USERNAME}. They behave exactly like
 *       environment variables, relaxed binding included;</li>
 *   <li>the {@link #DEFAULTS}, then {@code --spring.config.name} and {@code --server.port}, then the
 *       arguments the JVM was started with, as command-line arguments.</li>
 * </ul>
 *
 * <p>Services start one after another. If any of them fails to start, the ones already running
 * are closed and the JVM exits with status 1, so the orchestrator restarts the whole deployable
 * rather than leaving it half up.
 */
public final class ServiceHost {

    private static final Logger log = LoggerFactory.getLogger(ServiceHost.class);

    /**
     * Defaults every hosted service starts with, ahead of its own arguments so any of them can be
     * overridden. Virtual threads keep a handful of idle embedded web servers from each holding a
     * pool of platform threads; the banner and JMX are noise when several contexts share a JVM.
     */
    static final List<String> DEFAULTS = List.of(
            "--spring.threads.virtual.enabled=true",
            "--spring.main.banner-mode=off",
            "--spring.jmx.enabled=false");

    private ServiceHost() {
    }

    /**
     * Starts every service in order; on a failure closes the started ones and exits the JVM.
     *
     * @return the running contexts, in start order
     */
    public static List<ConfigurableApplicationContext> run(String[] args, HostedService... services) {
        try {
            return start(args, System.getenv(), services);
        } catch (RuntimeException e) {
            log.error("Could not start every service; exiting", e);
            System.exit(1);
            throw e;
        }
    }

    /**
     * Starts every service in order. If one fails, the services already started are closed and
     * the failure is rethrown.
     */
    static List<ConfigurableApplicationContext> start(String[] args, Map<String, String> environment,
                                                      HostedService... services) {
        List<ConfigurableApplicationContext> started = new ArrayList<>();
        for (HostedService service : services) {
            long begin = System.nanoTime();
            try {
                ConfigurableApplicationContext context = new SpringApplicationBuilder(service.application())
                        .environment(environmentFor(service, environment))
                        .run(argumentsFor(service, args));
                started.add(context);
                log.info("Started {} on port {} in {} ms", service.name(), service.port(),
                        (System.nanoTime() - begin) / 1_000_000);
            } catch (RuntimeException e) {
                for (int i = started.size() - 1; i >= 0; i--) {
                    started.get(i).close();
                }
                throw new IllegalStateException("Service " + service.name() + " failed to start", e);
            }
        }
        return started;
    }

    /** The command-line arguments one service is started with. */
    static String[] argumentsFor(HostedService service, String[] jvmArgs) {
        List<String> arguments = new ArrayList<>(DEFAULTS);
        arguments.add("--spring.config.name=" + service.name());
        arguments.add("--server.port=" + service.port());
        arguments.addAll(List.of(jvmArgs));
        return arguments.toArray(String[]::new);
    }

    /** The service's own environment variables, {@code <NAME>__VAR} seen as {@code VAR}. */
    static Map<String, Object> serviceVariables(HostedService service, Map<String, String> environment) {
        String prefix = service.environmentPrefix();
        Map<String, Object> variables = new LinkedHashMap<>();
        environment.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix) && e.getKey().length() > prefix.length())
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> variables.put(e.getKey().substring(prefix.length()), e.getValue()));
        return variables;
    }

    /**
     * A fresh environment per service: the JVM's environment variables, with the service's own
     * variables in front of them.
     */
    static StandardEnvironment environmentFor(HostedService service, Map<String, String> environment) {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> own = serviceVariables(service, environment);
        if (!own.isEmpty()) {
            env.getPropertySources().addBefore(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(service.name() + "Environment", own));
        }
        return env;
    }
}
