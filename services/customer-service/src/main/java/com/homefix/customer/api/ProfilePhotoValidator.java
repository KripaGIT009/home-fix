package com.homefix.customer.api;

import java.util.Set;

import org.springframework.web.multipart.MultipartFile;

import com.homefix.customer.service.CustomerException;

import org.springframework.http.HttpStatus;

/**
 * Validates an uploaded profile photo: JPEG or PNG, maximum 5 MB (Requirement 2.1).
 */
public final class ProfilePhotoValidator {

    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png");

    private ProfilePhotoValidator() {
    }

    /**
     * @throws CustomerException with a 400 status when the photo is the wrong type or too
     *         large.
     */
    public static void validate(MultipartFile photo) {
        if (photo == null || photo.isEmpty()) {
            return; // photo is optional
        }
        String contentType = photo.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw new CustomerException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO_TYPE",
                    "Profile photo must be JPEG or PNG");
        }
        if (photo.getSize() > MAX_BYTES) {
            throw new CustomerException(HttpStatus.BAD_REQUEST, "PHOTO_TOO_LARGE",
                    "Profile photo must not exceed 5 MB");
        }
    }
}
