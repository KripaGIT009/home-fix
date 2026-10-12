package com.homefix.auth.api;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.auth.api.EmailAuthRequests.AgencyDecisionEmailRequest;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.email.EmailDeliveryException;
import com.homefix.auth.email.EmailMessages;
import com.homefix.auth.email.EmailSenderPort;

/**
 * Emails other services ask the Auth Service to send, since it owns email delivery (email-auth
 * design D6, Requirement 7.3). Under {@code /internal}: the shared service credential only.
 */
@RestController
@RequestMapping("/internal/emails")
public class InternalEmailController {

    private static final Logger log = LoggerFactory.getLogger(InternalEmailController.class);

    private final UserAccountRepository userRepository;
    private final EmailSenderPort emailSender;

    public InternalEmailController(UserAccountRepository userRepository, EmailSenderPort emailSender) {
        this.userRepository = userRepository;
        this.emailSender = emailSender;
    }

    /**
     * {@code POST /internal/emails/agency-decision} — tell an agency applicant their application was
     * approved or rejected (Requirement 5.7). 202 also when the account has no verified email or the
     * mail could not be sent: the decision itself stands either way, and it is logged.
     *
     * @throws UserNotFoundException 404 for an unknown account
     */
    @PostMapping("/agency-decision")
    public ResponseEntity<Void> agencyDecision(@Valid @RequestBody AgencyDecisionEmailRequest request) {
        UserAccount account = userRepository.findById(request.userId())
                .orElseThrow(() -> new UserNotFoundException(request.userId()));
        if (!account.isEmailVerified()) {
            log.info("Agency decision for account {} not emailed: no verified email", account.getId());
            return ResponseEntity.accepted().build();
        }
        try {
            emailSender.send(account.getEmail(), EmailMessages.agencyDecision(request.tenantName(),
                    request.approved(), request.reason()));
        } catch (EmailDeliveryException ex) {
            log.warn("Agency decision email for account {} could not be sent: {}", account.getId(), ex.getMessage());
        }
        return ResponseEntity.accepted().build();
    }
}
