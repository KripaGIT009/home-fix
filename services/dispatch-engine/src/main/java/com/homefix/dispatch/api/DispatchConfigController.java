package com.homefix.dispatch.api;

import com.homefix.dispatch.domain.DispatchSettingsValidationException;
import com.homefix.dispatch.domain.WeightValidationException;
import com.homefix.dispatch.service.DispatchSettingsService;
import com.homefix.shared.observability.error.ErrorResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Portal endpoint for the dispatch rules — matching weights plus search radius and offer
 * timeout — read and written as one form (Requirements 8.2, 8.4, 8.5, 8.8, 19.5).
 *
 * <p>The weights obey the same accept-iff-valid rule as {@link DispatchWeightsController} and fail
 * with the same {@code INVALID_DISPATCH_WEIGHTS} code; the radius / cycle / timeout values fail
 * with {@code INVALID_DISPATCH_SETTINGS}. Either way nothing is changed. Updates apply to new
 * dispatches immediately and last until the service restarts (see {@link DispatchSettingsService}).
 *
 * <p>RBAC is enforced upstream by the shared {@code RbacEnforcementFilter}: dispatchers may read the
 * rules they work under, but changing them is System Configuration and needs SUPER_ADMIN
 * ({@code DispatchRbacConfig}).
 */
@RestController
@RequestMapping("/admin/dispatch/config")
public class DispatchConfigController {

    private final DispatchSettingsService settingsService;

    public DispatchConfigController(DispatchSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public DispatchConfigDto current() {
        return DispatchConfigDto.from(settingsService.current());
    }

    @PutMapping
    public DispatchConfigDto update(@Valid @RequestBody DispatchConfigDto request) {
        // toSettings validates the weights before the service validates the rest, and the service
        // applies nothing until everything has passed.
        return DispatchConfigDto.from(settingsService.update(request.toSettings()));
    }

    @ExceptionHandler(WeightValidationException.class)
    public ResponseEntity<ErrorResponseDto> onInvalidWeights(WeightValidationException ex) {
        return badRequest("INVALID_DISPATCH_WEIGHTS", ex.getMessage());
    }

    @ExceptionHandler(DispatchSettingsValidationException.class)
    public ResponseEntity<ErrorResponseDto> onInvalidSettings(DispatchSettingsValidationException ex) {
        return badRequest("INVALID_DISPATCH_SETTINGS", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> onMissingField(MethodArgumentNotValidException ex) {
        ErrorResponseDto.Builder body = ErrorResponseDto.builder()
                .errorCode("VALIDATION_ERROR")
                .message("Request validation failed");
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> body.addDetail(fe.getField() + ": " + fe.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body.build());
    }

    private static ResponseEntity<ErrorResponseDto> badRequest(String errorCode, String message) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode(errorCode)
                .message(message)
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
