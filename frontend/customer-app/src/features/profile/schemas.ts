import { z } from 'zod';
import { emailField, newPasswordField } from '@features/auth/schemas';

/**
 * Profile "Email & password" forms (email-auth Requirement 4). Both ask for
 * the current password only when the account already has one (4.2), so each
 * schema is built for the account it edits.
 */

/** The current password: required when the account has one, otherwise unused. */
function currentPasswordField(required: boolean) {
  return required ? z.string().min(1, 'Enter your current password') : z.string();
}

/** Add or change the account's email. */
export function emailChangeSchema(hasPassword: boolean) {
  return z.object({
    email: emailField,
    currentPassword: currentPasswordField(hasPassword),
  });
}

export type EmailChangeFormValues = z.infer<ReturnType<typeof emailChangeSchema>>;

/** Set a first password, or change the current one. */
export function passwordChangeSchema(hasPassword: boolean) {
  return z
    .object({
      currentPassword: currentPasswordField(hasPassword),
      newPassword: newPasswordField,
      confirmPassword: z.string().min(1, 'Enter the password again'),
    })
    .refine((values) => values.newPassword === values.confirmPassword, {
      path: ['confirmPassword'],
      message: 'The passwords do not match',
    });
}

export type PasswordChangeFormValues = z.infer<ReturnType<typeof passwordChangeSchema>>;
