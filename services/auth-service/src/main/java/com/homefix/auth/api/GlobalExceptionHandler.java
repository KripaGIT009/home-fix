package com.homefix.auth.api;

import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.homefix.auth.admin.AdminUserException;
import com.homefix.auth.domain.AccountDisabledException;
import com.homefix.auth.emailauth.EmailAuthException;
import com.homefix.auth.password.PasswordLoginException;
import com.homefix.auth.registration.RegistrationException;
import com.homefix.auth.social.SocialIdentityException;
import com.homefix.auth.token.TokenException;
import com.homefix.shared.observability.error.ErrorResponseDto;

/**
 * Translates domain and validation exceptions into the shared {@link ErrorResponseDto}
 * envelope (Task 5), preserving the correlation ID placed on the MDC by the shared
 * {@code CorrelationIdFilter} (Task 4).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_MDC_KEY = "correlationId";

    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<ErrorResponseDto> handleRegistration(RegistrationException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode(ex.getErrorCode())
                .message(ex.getMessage())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build();

        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getStatus());
        if (ex.getRetryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()));
        }
        return response.body(body);
    }

    @ExceptionHandler(PasswordLoginException.class)
    public ResponseEntity<ErrorResponseDto> handlePasswordLogin(PasswordLoginException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode(ex.getErrorCode())
                .message(ex.getMessage())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build();

        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getStatus());
        if (ex.getRetryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()));
        }
        return response.body(body);
    }

    /** Email sign-up, codes, reset, credentials and invitations (email-auth spec). */
    @ExceptionHandler(EmailAuthException.class)
    public ResponseEntity<ErrorResponseDto> handleEmailAuth(EmailAuthException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode(ex.getErrorCode())
                .message(ex.getMessage())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build();

        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getStatus());
        if (ex.getRetryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()));
        }
        return response.body(body);
    }

    @ExceptionHandler(TokenException.class)
    public ResponseEntity<ErrorResponseDto> handleToken(TokenException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode(ex.getErrorCode())
                .message(ex.getMessage())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build();
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(SocialIdentityException.class)
    public ResponseEntity<ErrorResponseDto> handleSocialIdentity(SocialIdentityException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode(ex.getErrorCode())
                .message(ex.getMessage())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build();
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleUserNotFound(UserNotFoundException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode(UserNotFoundException.ERROR_CODE)
                .message(ex.getMessage())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * A sign-in or refresh by an account an administrator has suspended or deactivated
     * (Requirement 19.2): 403 {@code ACCOUNT_DISABLED}.
     */
    @ExceptionHandler(AccountDisabledException.class)
    public ResponseEntity<ErrorResponseDto> handleAccountDisabled(AccountDisabledException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode(ex.getErrorCode())
                .message(ex.getMessage())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build();
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /**
     * A refused Admin Portal user-management action (self-change, ADMIN acting on an admin), or an
     * internal role change naming a role other than TENANT_ADMIN.
     */
    @ExceptionHandler(AdminUserException.class)
    public ResponseEntity<ErrorResponseDto> handleAdminUser(AdminUserException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode(ex.getErrorCode())
                .message(ex.getMessage())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build();
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /**
     * An unreadable body (malformed JSON, or an enum value such as an unknown account status)
     * is a 400 in the shared envelope rather than the framework's default error body.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDto> handleUnreadable(HttpMessageNotReadableException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode("VALIDATION_ERROR")
                .message("Request body is missing or malformed")
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** A malformed path variable (e.g. a non-UUID user id) is a client error, not a 500. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponseDto> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode("VALIDATION_ERROR")
                .message("Request validation failed")
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .addDetail(ex.getName() + ": invalid value")
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** A missing required query parameter (e.g. {@code mobileNumber} on the internal lookup). */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponseDto> handleMissingParameter(MissingServletRequestParameterException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode("VALIDATION_ERROR")
                .message("Request validation failed")
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .addDetail(ex.getParameterName() + ": is required")
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleValidation(MethodArgumentNotValidException ex) {
        ErrorResponseDto.Builder builder = ErrorResponseDto.builder()
                .errorCode("VALIDATION_ERROR")
                .message("Request validation failed")
                .correlationId(MDC.get(CORRELATION_MDC_KEY));
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> builder.addDetail(fe.getField() + ": " + fe.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(builder.build());
    }
}
