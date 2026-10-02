package com.homefix.notification.contact;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.homefix.notification.config.NotificationProperties;
import com.homefix.notification.domain.NotificationContact;

/**
 * {@link ContactDirectoryPort} backed by the Auth Service's internal contact endpoint,
 * {@code GET /internal/users/{userId}/contact}.
 *
 * <p>The endpoint is service-to-service only and is authenticated with the platform's shared
 * internal credential in the {@code X-Internal-Api-Key} header (the {@code INTERNAL_API_KEY}
 * convention the Booking Service's internal dispatch endpoints use).
 *
 * <p>Failure modes are split deliberately:
 * <ul>
 *   <li><b>200</b> — the user's addresses; any of them may be null.</li>
 *   <li><b>404 {@code USER_NOT_FOUND}</b> — no such account. Permanent, so it is reported as
 *       {@link Optional#empty()} and the caller skips the address-bound channels instead of
 *       retrying for ever.</li>
 *   <li><b>Anything else</b> — timeout, connection failure, 5xx, a 401 from a mismatched
 *       credential, or a 404 that is not {@code USER_NOT_FOUND} (an Auth Service without this
 *       endpoint) — raises {@link ContactLookupException}. The consumer's retry/dead-letter path
 *       then handles it, so a misconfiguration or outage loses nothing: the events wait in the
 *       dead-letter topic for replay rather than being silently skipped.</li>
 * </ul>
 *
 * <p>Connect and read timeouts are bounded ({@code homefix.notification.clients.contact-lookup-timeout},
 * default 2 s) so one slow lookup cannot consume the 10-second delivery budget of
 * Requirement 17.4. There is no retry here: the consumer already retries the whole event, and
 * retrying at both levels would multiply the delay. No address is ever logged (Requirement 26.4).
 */
@Component
public class AuthServiceContactDirectoryAdapter implements ContactDirectoryPort {

    /** Header carrying the shared service credential the Auth Service expects on /internal/**. */
    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    static final String USER_NOT_FOUND = "USER_NOT_FOUND";

    private static final Logger log = LoggerFactory.getLogger(AuthServiceContactDirectoryAdapter.class);

    private static final ParameterizedTypeReference<Map<String, Object>> ERROR_BODY =
            new ParameterizedTypeReference<>() { };

    private final RestClient restClient;

    @Autowired
    public AuthServiceContactDirectoryAdapter(NotificationProperties properties,
                                              @Value("${homefix.notification.internal-api-key:}") String internalApiKey) {
        this(properties.getClients().getAuthServiceBaseUrl(), internalApiKey,
                properties.getClients().getContactLookupTimeout());
    }

    AuthServiceContactDirectoryAdapter(String baseUrl, String internalApiKey, Duration timeout) {
        if (internalApiKey == null || internalApiKey.isBlank()) {
            // Fail at startup rather than on the first event: without the credential every lookup
            // is refused and every notification would be dead-lettered.
            throw new IllegalStateException(
                    "homefix.notification.internal-api-key is not configured; the Notification Service "
                            + "cannot look up recipient contact details in the Auth Service without it");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) timeout.toMillis());
        requestFactory.setReadTimeout((int) timeout.toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(INTERNAL_KEY_HEADER, internalApiKey)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public Optional<NotificationContact> findContact(UUID userId) {
        try {
            return restClient.get()
                    .uri("/internal/users/{userId}/contact", userId)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (response.getStatusCode().is2xxSuccessful()) {
                            ContactBody body = response.bodyTo(ContactBody.class);
                            if (body == null) {
                                throw new ContactLookupException("Auth Service returned an empty contact body");
                            }
                            return Optional.of(new NotificationContact(body.mobileNumber(), body.emailAddress(), null));
                        }
                        if (status == 404 && isUserNotFound(response.bodyTo(ERROR_BODY))) {
                            return Optional.<NotificationContact>empty();
                        }
                        if (status == 401 || status == 403) {
                            log.error("Auth Service refused the contact lookup with HTTP {}; check that "
                                    + "INTERNAL_API_KEY matches across services", status);
                        }
                        throw new ContactLookupException("Auth Service contact lookup returned HTTP " + status);
                    });
        } catch (ContactLookupException e) {
            throw e;
        } catch (RestClientException | IllegalStateException e) {
            // Timeouts and connection failures surface as ResourceAccessException; an unreadable
            // body as RestClientException. Either way the directory was not consulted.
            throw new ContactLookupException("Auth Service contact lookup failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private static boolean isUserNotFound(Map<String, Object> body) {
        return body != null && USER_NOT_FOUND.equals(body.get("errorCode"));
    }

    /** The subset of the Auth Service response this service reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContactBody(UUID userId, String mobileNumber, String emailAddress) {
    }
}
