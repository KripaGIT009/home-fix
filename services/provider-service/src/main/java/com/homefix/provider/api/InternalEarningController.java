package com.homefix.provider.api;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.provider.api.dto.JobEarningCreditRequest;
import com.homefix.provider.domain.ProviderProfile;
import com.homefix.provider.service.ProviderService;

import jakarta.validation.Valid;

/**
 * Service-to-service write surface for the Payment Service: crediting a paid booking's earning to
 * the provider's wallet (Requirements 12.10, 14.1).
 *
 * <p>Reachable only with the shared {@code X-Internal-Api-Key} credential, like
 * {@link InternalProviderController}. Idempotent per booking — the Payment Service re-sends a
 * credit it could not confirm — so a repeat answers 200 with the balance unchanged.
 */
@RestController
@RequestMapping("/internal/providers")
public class InternalEarningController {

    private final ProviderService providerService;

    public InternalEarningController(ProviderService providerService) {
        this.providerService = providerService;
    }

    /**
     * {@code POST /internal/providers/{providerId}/earnings} — credit one booking's job earning.
     *
     * @return 200 with the wallet balance after the credit; 404 {@code PROVIDER_NOT_FOUND} for an
     *         unknown provider; 400 {@code VALIDATION_ERROR} for a fee above the gross
     */
    @PostMapping("/{providerId}/earnings")
    public ResponseEntity<WalletBalanceResponse> creditJobEarning(
            @PathVariable("providerId") UUID providerId,
            @Valid @RequestBody JobEarningCreditRequest request) {
        ProviderProfile profile = providerService.creditJobEarning(providerId, request.bookingId(),
                request.bookingReference(), request.gross(), request.platformFee());
        return ResponseEntity.ok(new WalletBalanceResponse(providerId, profile.getWalletBalance()));
    }

    /** The provider's wallet balance after a credit. */
    public record WalletBalanceResponse(UUID providerId, BigDecimal walletBalance) {
    }
}
