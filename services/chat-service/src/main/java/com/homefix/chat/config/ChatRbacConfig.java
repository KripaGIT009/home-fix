package com.homefix.chat.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Chat Service (Requirement 18).
 *
 * <p>Without these rules the shared {@code RbacEnforcementFilter} finds no matching pattern and
 * falls through, so the REST chat surface would be gated only by "is this token valid".
 *
 * <h2>Why {@code @PostConstruct} and not {@code ApplicationReadyEvent}</h2>
 * <p>The rules must be in place <em>before</em> the embedded server accepts its first request.
 * {@code ApplicationReadyEvent} fires only after context refresh completes, and the web server is
 * already bound and serving by then — leaving a window in which the allow-list is empty. Because
 * the filter passes a request through whenever no pattern matches, every endpoint would be open to
 * any valid token for the duration of that window. {@code @PostConstruct} runs as part of this
 * bean's initialisation, during refresh, so no such window exists. The method is {@code public}
 * purely so tests can drive it directly.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li><strong>Ordering matters.</strong> {@code RbacEnforcementFilter} takes the <em>first</em>
 *       matching entry of this {@code LinkedHashMap} and stops, so specific patterns must precede
 *       any catch-all. The two rules below are disjoint (they differ only by HTTP method), so
 *       neither can shadow the other.</li>
 *   <li><strong>The role list is a logical OR.</strong> Holding any one of the listed roles is
 *       sufficient; the authority compared is {@code ROLE_<NAME>}.</li>
 * </ul>
 *
 * <h2>Why staff roles are deliberately absent</h2>
 * <p>Only the two booking participants — the CUSTOMER and the SERVICE_PROVIDER — are listed. Chat
 * access is not a role question but a participant question: {@code ChatService} resolves the
 * channel's stored {@code (customerId, providerId)} pair and rejects anyone else with a 403
 * (Requirement 18.7, Property 23). An ADMIN or SUPPORT_AGENT principal is not a participant, so
 * adding those roles here would let the request past the filter only to be refused one layer
 * deeper — creating a misleading impression that staff have a read path into private customer
 * conversations when they do not. Role and participation must agree, so the coarse gate is kept as
 * narrow as the fine one.
 *
 * <h2>Endpoints deliberately left without a rule</h2>
 * <p>{@code RbacEnforcementFilter} is installed <em>inside</em> the {@code SecurityFilterChain}
 * (see {@link WebSecurityConfig}) via {@code addFilterAfter(...)}, so it runs for every request
 * reaching the chain, including those marked {@code permitAll()}. A rule matching a public path
 * therefore converts that path into a 401. Two surfaces are consequently left unmapped:
 * <ul>
 *   <li><strong>{@code /ws/chat}</strong> — the STOMP WebSocket handshake endpoint registered by
 *       {@link WebSocketConfig}. Its authorization is enforced separately, per-frame, by
 *       {@link WebSocketAuthorizationConfig}; an HTTP-shaped rule here would break the upgrade
 *       handshake rather than secure it.</li>
 *   <li><strong>The health / metrics surface</strong> ({@code /health/**}, {@code /actuator/**},
 *       {@code /metrics}, {@code /prometheus}) — scraped by Kubernetes probes and Prometheus,
 *       neither of which presents a bearer token.</li>
 * </ul>
 */
@Configuration
public class ChatRbacConfig {

    /**
     * The two roles a booking participant can hold. Staff roles are intentionally excluded — see
     * the class Javadoc.
     */
    private static final List<String> CHANNEL_PARTICIPANTS =
            List.of("CUSTOMER", "SERVICE_PROVIDER");

    private final RbacProperties rbacProperties;

    public ChatRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    /**
     * Registers the chat endpoint to role rules. Adds no entry for the WebSocket handshake nor the
     * health/actuator surface — see the class Javadoc.
     */
    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        // Send a message on a booking's channel (Requirement 18.2, 18.6, 18.7).
        rules.put("POST /chat/channels/*/messages", CHANNEL_PARTICIPANTS);
        // Read the booking's message history (Requirement 18.4, 18.7).
        rules.put("GET /chat/channels/*/messages", CHANNEL_PARTICIPANTS);
    }
}
