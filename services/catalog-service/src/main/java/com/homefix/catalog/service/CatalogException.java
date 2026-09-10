package com.homefix.catalog.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Domain exception for catalog category/subcategory failures, carrying the HTTP status, a
 * stable error code, and optional details (e.g. blocking provider/booking identifiers) that
 * the REST layer surfaces via the shared {@code ErrorResponseDto}.
 */
public class CatalogException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<String> details = new ArrayList<>();

    public CatalogException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public CatalogException(HttpStatus status, String errorCode, String message, List<String> details) {
        this(status, errorCode, message);
        if (details != null) {
            this.details.addAll(details);
        }
    }

    public static CatalogException validation(String message) {
        return new CatalogException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public static CatalogException categoryNotFound(String message) {
        return new CatalogException(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", message);
    }

    public static CatalogException subcategoryNotFound(String message) {
        return new CatalogException(HttpStatus.NOT_FOUND, "SUBCATEGORY_NOT_FOUND", message);
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
