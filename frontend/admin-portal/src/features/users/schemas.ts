import { z } from 'zod';

/**
 * Staff invitation form (email-auth Requirement 6.1): an email and one staff
 * role. The roles offered depend on the inviter (ADMIN only for a SUPER_ADMIN);
 * the Auth Service enforces the same rule and answers 403 SUPER_ADMIN_REQUIRED.
 */
export const invitationSchema = z.object({
  email: z
    .string()
    .trim()
    .min(1, 'Enter an email address.')
    .max(254, 'Email must be at most 254 characters.')
    .email('Enter a valid email address.'),
  role: z.enum(['ADMIN', 'FINANCE_ADMIN', 'DISPATCHER', 'SUPPORT_AGENT'], {
    errorMap: () => ({ message: 'Choose a role.' }),
  }),
});

export type InvitationFormValues = z.infer<typeof invitationSchema>;
