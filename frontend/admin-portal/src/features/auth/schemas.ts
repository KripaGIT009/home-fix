import { z } from 'zod';
import {
  MOBILE_NUMBER_REGEX,
  OTP_LENGTH,
  PASSWORD_MAX_LENGTH,
  PASSWORD_MIN_LENGTH,
} from './constants';

/** Mobile-number step schema (Requirement 1.1). */
export const mobileSchema = z.object({
  mobileNumber: z
    .string()
    .trim()
    .regex(MOBILE_NUMBER_REGEX, 'Enter a valid 10-digit mobile number'),
});

export type MobileFormValues = z.infer<typeof mobileSchema>;

/** OTP step schema — exactly OTP_LENGTH digits (Requirement 1.2). */
export const otpSchema = z.object({
  otp: z
    .string()
    .trim()
    .regex(new RegExp(`^\\d{${OTP_LENGTH}}$`), `Enter the ${OTP_LENGTH}-digit code`),
});

export type OtpFormValues = z.infer<typeof otpSchema>;

/**
 * Email-or-username/password sign-in schema (email-auth Requirement 2.5).
 *
 * The identifier is not checked for an @: a console username is just as valid,
 * and the service tells the two apart. Length bounds only. Sign-in must not apply a strength policy: the rule that
 * matters is whether the password matches the stored hash, and rejecting a
 * valid-but-short existing password in the browser would lock the account out
 * of a console it is entitled to open.
 */
export const credentialsSchema = z.object({
  identifier: z
    .string()
    .trim()
    .min(1, 'Enter your email or username')
    .max(254, 'That is too long for an email or username'),
  password: z.string().min(1, 'Enter your password').max(128, 'Password is too long'),
});

export type CredentialsFormValues = z.infer<typeof credentialsSchema>;

/** A required email address, as every email-auth endpoint takes it. */
const emailField = z
  .string()
  .trim()
  .min(1, 'Enter your email address')
  .max(254, 'Email must be at most 254 characters')
  .email('Enter a valid email address');

/**
 * A new password, mirroring the Auth Service's policy (email-auth Requirement
 * 1.2): 8–72 characters with at least one letter and one digit. Unlike the
 * sign-in field this one does apply the rule, because the server would refuse
 * it with WEAK_PASSWORD anyway.
 */
export const newPasswordField = z
  .string()
  .min(PASSWORD_MIN_LENGTH, `Use at least ${PASSWORD_MIN_LENGTH} characters`)
  .max(PASSWORD_MAX_LENGTH, `Use at most ${PASSWORD_MAX_LENGTH} characters`)
  .refine((value) => /\p{L}/u.test(value) && /\p{Nd}/u.test(value), {
    message: 'Use at least one letter and one number',
  });

/** Display name as the Auth Service bounds it (2–80 characters). */
const displayNameField = z
  .string()
  .trim()
  .min(2, 'Name must be at least 2 characters')
  .max(80, 'Name must be at most 80 characters');

/** Email sign-up for an agency applicant (email-auth Requirements 1, 5.1). */
export const emailSignupSchema = z
  .object({
    displayName: displayNameField,
    email: emailField,
    mobileNumber: mobileSchema.shape.mobileNumber,
    password: newPasswordField,
    confirmPassword: z.string(),
  })
  .refine((values) => values.password === values.confirmPassword, {
    message: 'The passwords do not match',
    path: ['confirmPassword'],
  });

export type EmailSignupFormValues = z.infer<typeof emailSignupSchema>;

/** Step 1 of a password reset: the account's email (Requirement 3.1). */
export const forgotPasswordSchema = z.object({ email: emailField });

export type ForgotPasswordFormValues = z.infer<typeof forgotPasswordSchema>;

/** Step 2 of a password reset: the emailed code and the new password (Requirement 3.2). */
export const resetPasswordSchema = z
  .object({
    code: otpSchema.shape.otp,
    newPassword: newPasswordField,
    confirmPassword: z.string(),
  })
  .refine((values) => values.newPassword === values.confirmPassword, {
    message: 'The passwords do not match',
    path: ['confirmPassword'],
  });

export type ResetPasswordFormValues = z.infer<typeof resetPasswordSchema>;

/** Accepting a staff invitation with a new account (email-auth Requirement 6.3). */
export const newAccountInvitationSchema = z
  .object({
    displayName: displayNameField,
    mobileNumber: mobileSchema.shape.mobileNumber,
    password: newPasswordField,
    confirmPassword: z.string(),
  })
  .refine((values) => values.password === values.confirmPassword, {
    message: 'The passwords do not match',
    path: ['confirmPassword'],
  });

export type NewAccountInvitationFormValues = z.infer<typeof newAccountInvitationSchema>;

/**
 * Accepting a staff invitation into an existing account (Requirement 6.4): its
 * current password, checked by the server, so no strength rule here.
 */
export const existingAccountInvitationSchema = credentialsSchema.pick({ password: true });

export type ExistingAccountInvitationFormValues = z.infer<typeof existingAccountInvitationSchema>;
