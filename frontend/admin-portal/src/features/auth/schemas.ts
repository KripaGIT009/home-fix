import { z } from 'zod';
import { MOBILE_NUMBER_REGEX, OTP_LENGTH } from './constants';

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
 * Username/password sign-in schema.
 *
 * Length bounds only. Sign-in must not apply a strength policy: the rule that
 * matters is whether the password matches the stored hash, and rejecting a
 * valid-but-short existing password in the browser would lock the account out
 * of a console it is entitled to open.
 */
export const credentialsSchema = z.object({
  username: z.string().trim().min(1, 'Enter your username').max(64, 'Username is too long'),
  password: z.string().min(1, 'Enter your password').max(128, 'Password is too long'),
});

export type CredentialsFormValues = z.infer<typeof credentialsSchema>;
