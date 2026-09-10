package com.homefix.dispatch.api;

import com.homefix.dispatch.domain.MatchingWeights;
import com.homefix.dispatch.domain.MatchingWeightsStore;
import com.homefix.dispatch.domain.WeightValidationException;
import com.homefix.shared.observability.error.ErrorResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoint for viewing and updating the dispatch matching weights (Requirement 19.5,
 * Property 19). A submitted weight set is accepted if and only if every weight is in [0.0, 1.0]
 * and the five sum to exactly 1.0; otherwise the update is rejected with a 400 that names the
 * failing condition, and the existing weights are left unchanged.
 *
 * <p>RBAC (SUPER_ADMIN for System Configuration) is enforced upstream by the shared
 * {@code RbacEnforcementFilter}; this controller focuses on domain validation.
 */
@RestController
@RequestMapping("/admin/dispatch/weights")
public class DispatchWeightsController {

    private final MatchingWeightsStore weightsStore;

    public DispatchWeightsController(MatchingWeightsStore weightsStore) {
        this.weightsStore = weightsStore;
    }

    @GetMapping
    public MatchingWeights current() {
        return weightsStore.current();
    }

    @PutMapping
    public MatchingWeights update(@Valid @RequestBody MatchingWeightsRequest request) {
        // ofExact validates range + exact sum before the store swaps; on failure it throws and the
        // store is never touched, satisfying "leave existing weights unchanged" (Requirement 19.5).
        MatchingWeights candidate = MatchingWeights.ofExact(
                request.distanceWeight(),
                request.availabilityWeight(),
                request.ratingWeight(),
                request.skillWeight(),
                request.performanceWeight());
        return weightsStore.update(candidate);
    }

    @ExceptionHandler(WeightValidationException.class)
    public ResponseEntity<ErrorResponseDto> onInvalidWeights(WeightValidationException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode("INVALID_DISPATCH_WEIGHTS")
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
