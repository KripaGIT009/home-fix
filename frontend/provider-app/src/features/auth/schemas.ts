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
