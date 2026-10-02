package com.homefix.auth.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.auth.domain.UserAccountRepository;

/**
 * Service-to-service lookups on user accounts, under {@code /internal}.
 *
 * <p>Every customer and provider registers through the Auth Service, so it is the one place that
 * knows how to reach a user. Domain events carry only user ids; the Notification Service resolves
 * the recipient's addresses here at send time (Requirement 17.4), which keeps phone numbers out of
 * Kafka and out of every producer.
 *
 * <p>Not reachable by end users: {@code /internal/**} requires the shared service credential in
 * {@code X-Internal-Api-Key} (see {@code InternalApiKeyFilter}) and is not routed by the API
 * Gateway. Nothing here logs the returned addresses (Requirement 26.4).
 */
@RestController
@RequestMapping("/internal/users")
public class InternalUserContactController {

    private final UserAccountRepository userRepository;

    public InternalUserContactController(UserAccountRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * {@code GET /internal/users/{userId}/contact} — the user's delivery addresses.
     *
     * @return 200 with {@link UserContactResponse} (addresses may be null); 404
     *         {@code USER_NOT_FOUND} when no account has that id
     */
    @GetMapping("/{userId}/contact")
    public ResponseEntity<UserContactResponse> contact(@PathVariable("userId") UUID userId) {
        return userRepository.findById(userId)
                .map(UserContactResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }
}
