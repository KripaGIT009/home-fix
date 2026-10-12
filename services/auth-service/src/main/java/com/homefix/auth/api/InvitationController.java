package com.homefix.auth.api;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.auth.api.EmailAuthRequests.InvitationAcceptanceRequest;
import com.homefix.auth.api.EmailAuthRequests.InvitationRequest;
import com.homefix.auth.invitation.StaffInvitationService;
import com.homefix.auth.invitation.StaffInvitationService.AcceptCommand;
import com.homefix.auth.invitation.StaffInvitationService.InvitationPreview;
import com.homefix.auth.invitation.StaffInvitationService.InvitationView;
import com.homefix.auth.password.PasswordLoginService.LoginResult;

/**
 * Staff invitations (email-auth Requirement 6).
 *
 * <ul>
 *   <li>{@code /admin/invitations} — list, create and revoke; ADMIN and SUPER_ADMIN by the RBAC rule
 *       in {@code AuthRbacConfig}, and only SUPER_ADMIN for an ADMIN invitation, by the service.</li>
 *   <li>{@code /auth/invitations/{token}} — the invitee's link: public, the token is the proof.</li>
 * </ul>
 */
@RestController
public class InvitationController {

    private final StaffInvitationService invitationService;

    public InvitationController(StaffInvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @GetMapping("/admin/invitations")
    public List<InvitationView> list() {
        return invitationService.listUsable();
    }

    @PostMapping("/admin/invitations")
    public ResponseEntity<InvitationView> invite(@Valid @RequestBody InvitationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(invitationService.invite(RequestContext.currentActor(), request.email(), request.role()));
    }

    @DeleteMapping("/admin/invitations/{id}")
    public ResponseEntity<Void> revoke(@PathVariable("id") UUID id) {
        invitationService.revoke(RequestContext.currentActor(), id);
        return ResponseEntity.noContent().build();
    }

    /** {@code GET /auth/invitations/{token}} — 410 {@code INVITATION_EXPIRED} for any unusable link. */
    @GetMapping("/auth/invitations/{token}")
    public InvitationPreview preview(@PathVariable("token") String token) {
        return invitationService.preview(token);
    }

    /** {@code POST /auth/invitations/{token}/acceptance} — signed in with the invited role. */
    @PostMapping("/auth/invitations/{token}/acceptance")
    public TokenResponse accept(@PathVariable("token") String token,
                                @Valid @RequestBody InvitationAcceptanceRequest request,
                                HttpServletRequest http) {
        LoginResult result = invitationService.accept(token, new AcceptCommand(request.displayName(),
                request.mobileNumber(), request.password()), RequestContext.clientIp(http));
        return TokenResponse.from(result.userId(), result.roles(), result.tokens());
    }
}
