package com.homefix.invoice.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Domain exception for invoice query and authorization failures, carrying the HTTP status, a stable
 * error code, and optional detail messages that the REST layer surfaces via the shared
 * {@code ErrorResponseDto}.
 *
 * <p>Modelled deliberately on {@code com.homefix.payment.service.PaymentException} and its siblings
 * so the Invoice Service emits exactly the platform-standard error envelope rather than a new shape
 * of its own: this service previously had no domain exception and no
 * {@code GlobalExceptionHandler}, so an authorization failure raised here would otherwise have
 * surfaced as Boot's default whitelabel 500 body.
 */
public class InvoiceException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<String> details = new ArrayList<>();

    public InvoiceException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public InvoiceException(HttpStatus status, String errorCode, String message, List<String> details) {
        this(status, errorCode, message);
        if (details != null) {
            this.details.addAll(details);
        }
    }

    public static InvoiceException validation(String message) {
        return new InvoiceException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public static InvoiceException notFound(String message) {
        return new InvoiceException(HttpStatus.NOT_FOUND, "INVOICE_NOT_FOUND", message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public List<String> getDetails() {
        return details;
    }
}
