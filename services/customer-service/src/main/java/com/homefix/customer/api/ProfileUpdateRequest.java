package com.homefix.customer.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PUT /customers/{id}/profile} (Requirement 2.1).
 *
 * <p>The profile photo itself is uploaded separately (multipart) and validated for
 * JPEG/PNG type and 5 MB size; here we accept the resulting stored photo URL. Display
 * name must be 1-100 chars and the email must be well-formed.
 */
public record ProfileUpdateRequest(

        @NotBlank(message = "display name is required")
        @Size(min = 1, max = 100, message = "display name must be 1-100 characters")
        String displayName,

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address")
        String email,

        String photoUrl) {
}
