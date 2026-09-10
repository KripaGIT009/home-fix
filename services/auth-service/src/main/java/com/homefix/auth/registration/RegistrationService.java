package com.homefix.auth.registration;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.homefix.auth.config.OtpProperties;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.otp.OtpCodeGenerator;
import com.homefix.auth.otp.OtpSession;
import com.homefix.auth.otp.OtpStore;
import com.homefix.auth.sms.SmsDeliveryException;
import com.homefix.auth.sms.SmsGatewayPort;
import com.homefix.auth.token.TokenPair;
import com.homefix.auth.token.TokenService;

/**
 * Orchestrates the OTP registration flow (Requirement 1).
 *
 * <ul>
 *   <li>{@link #requestOtp} — validates the phone, enforces per-phone rate limiting
 *       (Requirement 23.4), sends the OTP via the {@link SmsGatewayPort}, and only stores a
 *       pending session if SMS delivery succeeds (Requirement 1.16).</li>
 *   <li>{@link #verifyOtp} — validates the code, enforces the 5-attempt / 30-minute lockout
 *       (Requirement 1.3, Property 25), creates a verified {@link UserAccount}, and returns a
 *       JWT access token + refresh token (Requirement 1.2, 1.8).</li>
 * </ul>
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final OtpProperties otpProperties;
    private final OtpStore otpStore;
    private final OtpCodeGenerator codeGenerator;
    private final SmsGatewayPort smsGateway;
    private final UserAccountRepository userRepository;
    private final TokenService tokenService;

    public RegistrationService(OtpProperties otpProperties,
                               OtpStore otpStore,
                               OtpCodeGenerator codeGenerator,
                               SmsGatewayPort smsGateway,
                               UserAccountRepository userRepository,
                               TokenService tokenService) {
        this.otpProperties = otpProperties;
        this.otpStore = otpStore;
        this.codeGenerator = codeGenerator;
        this.smsGateway = smsGateway;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
    }

    /**
     * Issues an OTP for the supplied phone/role.
     *
     * @throws RegistrationException 429 if rate limited or locked; 502 if SMS delivery fails
     */
    public long requestOtp(String mobileNumber, String roleName) {
        Role role = resolveRole(roleName);

        // Do not issue during an active lockout window (Property 25).
        if (otpStore.isLocked(mobileNumber)) {
            long remaining = otpStore.lockRemaining(mobileNumber).getSeconds();
            throw new RegistrationException(HttpStatus.TOO_MANY_REQUESTS, "OTP_SESSION_LOCKED",
                    "OTP session is locked. Try again in " + remaining + " seconds.", remaining);
        }

        // Per-phone rate limit: max N requests per window (Requirement 23.4).
        long count = otpStore.recordRequestAndCount(mobileNumber, otpProperties.getRateLimitWindow());
        if (count > otpProperties.getMaxRequestsPerWindow()) {
            long retryAfter = otpProperties.getRateLimitWindow().getSeconds();
            throw new RegistrationException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RATE_LIMIT_EXCEEDED",
                    "Maximum " + otpProperties.getMaxRequestsPerWindow()
                            + " OTP requests per hour exceeded for this number.", retryAfter);
        }

        String code = codeGenerator.generate(otpProperties.getLength());
        String message = "Your HomeFix verification code is " + code
                + ". It expires in " + otpProperties.getTtl().toMinutes() + " minutes.";

        // Send FIRST; only persist the pending session if delivery succeeds (Requirement 1.16).
        try {
            smsGateway.send(mobileNumber, message);
        } catch (SmsDeliveryException ex) {
            log.warn("OTP SMS delivery failed; no pending session created");
            throw new RegistrationException(HttpStatus.BAD_GATEWAY, "SMS_DELIVERY_FAILED",
                    "Failed to deliver OTP to the supplied mobile number.");
        }

        String codeHash = codeGenerator.hash(code, mobileNumber);
        otpStore.saveSession(mobileNumber, new OtpSession(codeHash, 0, role.name()),
                otpProperties.getTtl());

        return otpProperties.getTtl().getSeconds();
    }

    /**
     * Verifies a submitted OTP and, on success, creates a verified account and issues tokens.
     *
     * @throws RegistrationException 429 if locked; 400 if expired/invalid; on the Nth wrong
     *                               attempt the session is locked for the configured window
     */
    public VerificationResult verifyOtp(String mobileNumber, String submittedCode) {
        // Reject immediately while locked (Property 25).
        if (otpStore.isLocked(mobileNumber)) {
            long remaining = otpStore.lockRemaining(mobileNumber).getSeconds();
            throw new RegistrationException(HttpStatus.TOO_MANY_REQUESTS, "OTP_SESSION_LOCKED",
                    "OTP session is locked. Try again in " + remaining + " seconds.", remaining);
        }

        Optional<OtpSession> maybeSession = otpStore.findSession(mobileNumber);
        if (maybeSession.isEmpty()) {
            // Either never requested or expired past its 5-minute TTL (Requirement 1.4).
            throw new RegistrationException(HttpStatus.BAD_REQUEST, "OTP_EXPIRED_OR_MISSING",
                    "No active OTP for this number. Request a new one.");
        }

        OtpSession session = maybeSession.get();

        if (codeGenerator.matches(submittedCode, mobileNumber, session.codeHash())) {
            otpStore.clearSession(mobileNumber);
            return completeRegistration(mobileNumber, resolveRole(session.role()));
        }

        // Incorrect code — count the attempt and lock on reaching the threshold (Requirement 1.3).
        int attempts = otpStore.incrementAttempts(mobileNumber);
        if (attempts >= otpProperties.getMaxVerifyAttempts()) {
            otpStore.lock(mobileNumber, otpProperties.getLockout());
            long lockoutSeconds = otpProperties.getLockout().getSeconds();
            throw new RegistrationException(HttpStatus.TOO_MANY_REQUESTS, "OTP_SESSION_LOCKED",
                    "Too many incorrect attempts. Session locked for "
                            + otpProperties.getLockout().toMinutes() + " minutes.", lockoutSeconds);
        }

        int remaining = otpProperties.getMaxVerifyAttempts() - attempts;
        throw new RegistrationException(HttpStatus.BAD_REQUEST, "OTP_INCORRECT",
                "Incorrect OTP. " + remaining + " attempt(s) remaining.");
    }

    private VerificationResult completeRegistration(String mobileNumber, Role role) {
        UserAccount account = userRepository.findByMobileNumber(mobileNumber)
                .map(existing -> {
                    existing.addRole(role);
                    return userRepository.save(existing);
                })
                .orElseGet(() -> userRepository.save(UserAccount.createVerified(mobileNumber, role)));

        List<String> roles = account.getRoles().stream().map(Enum::name).sorted().toList();
        TokenPair tokens = tokenService.issueTokens(account.getId().toString(), roles);
        return new VerificationResult(account.getId().toString(), roles, tokens);
    }

    private Role resolveRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return Role.CUSTOMER;
        }
        try {
            return Role.valueOf(roleName.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new RegistrationException(HttpStatus.BAD_REQUEST, "INVALID_ROLE",
                    "Unsupported role: " + roleName);
        }
    }

    /**
     * Outcome of a successful verification.
     */
    public record VerificationResult(String userId, List<String> roles, TokenPair tokens) {
    }
}
