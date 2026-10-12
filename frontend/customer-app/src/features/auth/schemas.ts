import { z } from 'zod';
import {
  DISPLAY_NAME_MAX_LENGTH,
  DISPLAY_NAME_MIN_LENGTH,
  MOBILE_NUMBER_REGEX,
  OTP_LENGTH,
  PASSWORD_MAX_BYTES,
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

/** A 6-digit emailed code (sign-up, reset, email change). */
export const emailCodeField = z
  .string()
  .trim()
  .regex(new RegExp(`^\\d{${OTP_LENGTH}}$`), `Enter the ${OTP_LENGTH}-digit code`);

export const emailField = z
  .string()
  .trim()
  .min(1, 'Enter your email address')
  .email('Enter a valid email address')
  .max(254, 'Use at most 254 characters');

/**
 * A new password, matching the Auth Service rule (email-auth Requirement 1.2):
 * 8–72 characters (72 UTF-8 bytes, bcrypt's limit) with a letter and a digit.
 * Sign-in does not apply it, so a password set under an older rule still works.
 */
export const newPasswordField = z
  .string()
  .min(PASSWORD_MIN_LENGTH, `Use at least ${PASSWORD_MIN_LENGTH} characters`)
  .refine(
    (value) => new TextEncoder().encode(value).length <= PASSWORD_MAX_BYTES,
    `Use at most ${PASSWORD_MAX_BYTES} characters`,
  )
  .refine(
    (value) => /\p{L}/u.test(value) && /\p{Nd}/u.test(value),
    'Use at least one letter and one number',
  );

/** Shown under every new-password field before it has an error. */
export const PASSWORD_HINT = `At least ${PASSWORD_MIN_LENGTH} characters, with a letter and a number.`;

/** Email sign-in schema (email-auth Requirement 2). */
export const emailSignInSchema = z.object({
  email: emailField,
  password: z.string().min(1, 'Enter your password'),
});

export type EmailSignInFormValues = z.infer<typeof emailSignInSchema>;

/** Email sign-up schema (email-auth Requirement 1.1–1.2). */
export const signUpSchema = z
  .object({
    displayName: z
      .string()
      .trim()
      .min(DISPLAY_NAME_MIN_LENGTH, 'Enter your name')
      .max(DISPLAY_NAME_MAX_LENGTH, `Use at most ${DISPLAY_NAME_MAX_LENGTH} characters`),
    email: emailField,
    mobileNumber: mobileSchema.shape.mobileNumber,
    password: newPasswordField,
    confirmPassword: z.string().min(1, 'Enter the password again'),
  })
  .refine((values) => values.password === values.confirmPassword, {
    path: ['confirmPassword'],
    message: 'The passwords do not match',
  });

export type SignUpFormValues = z.infer<typeof signUpSchema>;

/** Code step of sign-up and email change. */
export const emailCodeSchema = z.object({ code: emailCodeField });

export type EmailCodeFormValues = z.infer<typeof emailCodeSchema>;

/** First step of a password reset: the account's email. */
export const forgotPasswordSchema = z.object({ email: emailField });

export type ForgotPasswordFormValues = z.infer<typeof forgotPasswordSchema>;

/** Second step of a password reset (email-auth Requirement 3.2). */
export const resetPasswordSchema = z
  .object({
    code: emailCodeField,
    newPassword: newPasswordField,
    confirmPassword: z.string().min(1, 'Enter the password again'),
  })
  .refine((values) => values.newPassword === values.confirmPassword, {
    path: ['confirmPassword'],
    message: 'The passwords do not match',
  });

export type ResetPasswordFormValues = z.infer<typeof resetPasswordSchema>;
