package com.homefix.admin.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.admin.rbac.AdminAuthorization;
import com.homefix.admin.rbac.AdminModule;
import com.homefix.admin.verification.VerificationQueueEntry;
import com.homefix.admin.verification.VerificationQueueService;

/**
 * Verification Queue module (Requirement 19.2, 19.3). Lists all providers in DOCUMENT_SUBMITTED
 * status sorted oldest first, each entry carrying its submitted documents with inline view URLs so
 * an Admin can review them without a separate download.
 */
@RestController
@RequestMapping("/admin/verification-queue")
public class VerificationQueueController {

    private final VerificationQueueService queueService;
    private final AdminAuthorization authorization;

    public VerificationQueueController(VerificationQueueService queueService,
                                       AdminAuthorization authorization) {
        this.queueService = queueService;
        this.authorization = authorization;
    }

    @GetMapping
    public ResponseEntity<List<VerificationQueueEntry>> queue(Authentication authentication) {
        authorization.requireAccess(authentication, AdminModule.VERIFICATION_QUEUE);
        return ResponseEntity.ok(queueService.queue());
    }
}
