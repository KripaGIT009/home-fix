import { z } from 'zod';
import {
  DISPLAY_NAME_MAX_LENGTH,
  DISPLAY_NAME_MIN_LENGTH,
  EMAIL_MAX_LENGTH,
  MOBILE_NUMBER_REGEX,
  OTP_LENGTH,
  PASSWORD_MAX_BYTES,
  PASSWORD_MIN_LENGTH,
} from './constants';
import { toE164 } from './phone';
import type { EmailSignupPayload } from './api';

/** A mobile number as typed: 10 digits, optionally with +91 (Requirement 1.1). */
const mobileNumberField = z
  .string()
  .trim()
  .regex(MOBILE_NUMBER_REGEX, 'Enter a valid 10-digit mobile number');

/** A 6-digit code, whether texted (OTP) or emailed. */
const codeField = z
  .string()
  .trim()
  .regex(new RegExp(`^\\d{${OTP_LENGTH}}$`), `Enter the ${OTP_LENGTH}-digit code`);

/** Mobile-number step schema (Requirement 1.1). */
export const mobileSchema = z.object({
  mobileNumber: mobileNumberField,
});

export type MobileFormValues = z.infer<typeof mobileSchema>;

/** OTP step schema — exactly OTP_LENGTH digits (Requirement 1.2). */
export const otpSchema = z.object({
  otp: codeField,
});

export type OtpFormValues = z.infer<typeof otpSchema>;

// ---------------------------------------------------------------------------
// Email sign-up, sign-in and reset (email-auth Requirements 1–4)
// ---------------------------------------------------------------------------

/** The password's length in UTF-8 bytes, the unit the Auth Service limits it in. */
function utf8Length(value: string): number {
  return new TextEncoder().encode(value).length;
}

/**
 * The Auth Service's password rule (Requirement 1.2), which stays
 * authoritative: 8–72 characters, at least one letter and one digit. Never
 * trimmed — a space is a legitimate password character.
 */
export const newPasswordField = z
  .string()
  .min(PASSWORD_MIN_LENGTH, `Use at least ${PASSWORD_MIN_LENGTH} characters`)
  .refine(
    (value) => utf8Length(value) <= PASSWORD_MAX_BYTES,
    `Use at most ${PASSWORD_MAX_BYTES} characters`,
  )
  .refine(
    (value) => /\p{L}/u.test(value) && /\p{Nd}/u.test(value),
    'Use at least one letter and one number',
  );

/** Helper text shown under a new-password field until it has an error. */
export const PASSWORD_HINT = `${PASSWORD_MIN_LENGTH}+ characters, with a letter and a number`;

const emailField = z
  .string()
  .trim()
  .min(1, 'Enter your email address')
  .max(EMAIL_MAX_LENGTH, `An email address can be at most ${EMAIL_MAX_LENGTH} characters`)
  .email('Enter a valid email address');

/** An existing password: sign-in never applies the new-password rule (old passwords stay valid). */
const currentPasswordField = z.string().min(1, 'Enter your password');

const MISMATCH = 'The passwords do not match';

/** Email sign-in (Requirement 2). */
export const emailSignInSchema = z.object({
  email: emailField,
  password: currentPasswordField,
});

export type EmailSignInFormValues = z.infer<typeof emailSignInSchema>;

/** Email sign-up: name, email, mobile, password typed twice (Requirement 1.1–1.2). */
export const signUpSchema = z
  .object({
    displayName: z
      .string()
      .trim()
      .min(
        DISPLAY_NAME_MIN_LENGTH,
        `Enter your name (at least ${DISPLAY_NAME_MIN_LENGTH} characters)`,
      )
      .max(
        DISPLAY_NAME_MAX_LENGTH,
        `Your name can be at most ${DISPLAY_NAME_MAX_LENGTH} characters`,
      ),
    email: emailField,
    mobileNumber: mobileNumberField,
    password: newPasswordField,
    confirmPassword: z.string().min(1, 'Type the password again'),
  })
  .refine((values) => values.password === values.confirmPassword, {
    message: MISMATCH,
    path: ['confirmPassword'],
  });

export type SignUpFormValues = z.input<typeof signUpSchema>;

/** The sign-up request body for validated form values, the mobile number in E.164. */
export function toEmailSignupPayload(values: SignUpFormValues): EmailSignupPayload {
  return {
    displayName: values.displayName.trim(),
    email: values.email.trim(),
    mobileNumber: toE164(values.mobileNumber),
    password: values.password,
  };
}

/** The emailed sign-up / email-change code. */
export const emailCodeSchema = z.object({
  code: codeField,
});

export type EmailCodeFormValues = z.infer<typeof emailCodeSchema>;

/** Forgotten password, step 1: where to send the reset code (Requirement 3.1). */
export const forgotPasswordSchema = z.object({
  email: emailField,
});

export type ForgotPasswordFormValues = z.infer<typeof forgotPasswordSchema>;

/** Forgotten password, step 2: the code and the new password twice (Requirement 3.2). */
export const resetPasswordSchema = z
  .object({
    code: codeField,
    newPassword: newPasswordField,
    confirmPassword: z.string().min(1, 'Type the new password again'),
  })
  .refine((values) => values.newPassword === values.confirmPassword, {
    message: MISMATCH,
    path: ['confirmPassword'],
  });

export type ResetPasswordFormValues = z.infer<typeof resetPasswordSchema>;

/**
 * Add or change the account's email (Requirement 4.1–4.2). The current
 * password is asked for only when the account has one.
 */
export function buildEmailChangeSchema(requireCurrentPassword: boolean) {
  return z.object({
    email: emailField,
    currentPassword: requireCurrentPassword ? currentPasswordField : z.string(),
  });
}

export type EmailChangeFormValues = z.infer<ReturnType<typeof buildEmailChangeSchema>>;

/** Set or change the account's password (Requirement 4.1–4.2). */
export function buildPasswordChangeSchema(requireCurrentPassword: boolean) {
  return z
    .object({
      currentPassword: requireCurrentPassword
        ? z.string().min(1, 'Enter your current password')
        : z.string(),
      newPassword: newPasswordField,
      confirmPassword: z.string().min(1, 'Type the new password again'),
    })
    .refine((values) => values.newPassword === values.confirmPassword, {
      message: MISMATCH,
      path: ['confirmPassword'],
    });
}

export type PasswordChangeFormValues = z.infer<ReturnType<typeof buildPasswordChangeSchema>>;
