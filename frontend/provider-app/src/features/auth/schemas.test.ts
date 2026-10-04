import { describe, expect, it } from 'vitest';
import {
  buildEmailChangeSchema,
  buildPasswordChangeSchema,
  emailCodeSchema,
  emailSignInSchema,
  newPasswordField,
  resetPasswordSchema,
  signUpSchema,
  toEmailSignupPayload,
  type SignUpFormValues,
} from './schemas';

/** The first issue's message, or null when the value passes. */
function firstError(result: { success: boolean; error?: { issues: { message: string }[] } }) {
  return result.success ? null : (result.error?.issues[0]?.message ?? null);
}

const SIGN_UP: SignUpFormValues = {
  displayName: '  Ravi Kumar ',
  email: ' ravi@example.com ',
  mobileNumber: '9876543210',
  password: 'secret123',
  confirmPassword: 'secret123',
};

describe('newPasswordField (the Auth Service password rule)', () => {
  it('accepts 8+ characters with a letter and a digit', () => {
    expect(newPasswordField.safeParse('abcdefg1').success).toBe(true);
    expect(newPasswordField.safeParse('пароль12').success).toBe(true);
  });

  it('refuses fewer than 8 characters', () => {
    expect(firstError(newPasswordField.safeParse('abc123'))).toBe('Use at least 8 characters');
  });

  it('refuses a password with no digit or no letter', () => {
    expect(firstError(newPasswordField.safeParse('abcdefgh'))).toBe(
      'Use at least one letter and one number',
    );
    expect(firstError(newPasswordField.safeParse('12345678'))).toBe(
      'Use at least one letter and one number',
    );
  });

  it('counts the 72 limit in UTF-8 bytes, as bcrypt and the server do', () => {
    expect(newPasswordField.safeParse(`${'a'.repeat(71)}1`).success).toBe(true);
    expect(firstError(newPasswordField.safeParse(`${'a'.repeat(72)}1`))).toBe(
      'Use at most 72 characters',
    );
    // 36 two-byte letters are 72 bytes; one more digit tips it over.
    expect(firstError(newPasswordField.safeParse(`${'é'.repeat(36)}1`))).toBe(
      'Use at most 72 characters',
    );
  });

  it('does not trim: spaces are part of the password', () => {
    expect(newPasswordField.parse(' secret12 ')).toBe(' secret12 ');
  });
});

describe('signUpSchema', () => {
  it('accepts a complete sign-up', () => {
    expect(signUpSchema.safeParse(SIGN_UP).success).toBe(true);
  });

  it('requires the two passwords to match, reported on the confirmation', () => {
    const result = signUpSchema.safeParse({ ...SIGN_UP, confirmPassword: 'secret124' });
    expect(result.success).toBe(false);
    expect(result.error?.issues[0]?.path).toEqual(['confirmPassword']);
  });

  it('checks the name length, the email and the mobile number', () => {
    expect(firstError(signUpSchema.safeParse({ ...SIGN_UP, displayName: ' R ' }))).toMatch(
      /at least 2/,
    );
    expect(firstError(signUpSchema.safeParse({ ...SIGN_UP, email: 'ravi@' }))).toBe(
      'Enter a valid email address',
    );
    expect(firstError(signUpSchema.safeParse({ ...SIGN_UP, mobileNumber: '12345' }))).toBe(
      'Enter a valid 10-digit mobile number',
    );
  });
});

describe('toEmailSignupPayload', () => {
  it('trims the name and email and sends the mobile number in E.164', () => {
    expect(toEmailSignupPayload(SIGN_UP)).toEqual({
      displayName: 'Ravi Kumar',
      email: 'ravi@example.com',
      mobileNumber: '+919876543210',
      password: 'secret123',
    });
  });
});

describe('emailSignInSchema', () => {
  it('does not apply the new-password rule to an existing password', () => {
    expect(emailSignInSchema.safeParse({ email: 'a@b.co', password: 'old' }).success).toBe(true);
    expect(firstError(emailSignInSchema.safeParse({ email: 'a@b.co', password: '' }))).toBe(
      'Enter your password',
    );
  });
});

describe('emailCodeSchema', () => {
  it('takes exactly 6 digits', () => {
    expect(emailCodeSchema.safeParse({ code: ' 123456 ' }).success).toBe(true);
    expect(emailCodeSchema.safeParse({ code: '12345' }).success).toBe(false);
    expect(emailCodeSchema.safeParse({ code: '12345a' }).success).toBe(false);
  });
});

describe('resetPasswordSchema', () => {
  it('needs the code, a valid new password and a matching confirmation', () => {
    const valid = { code: '123456', newPassword: 'newpass99', confirmPassword: 'newpass99' };
    expect(resetPasswordSchema.safeParse(valid).success).toBe(true);
    expect(firstError(resetPasswordSchema.safeParse({ ...valid, confirmPassword: 'x' }))).toBe(
      'The passwords do not match',
    );
    expect(
      firstError(
        resetPasswordSchema.safeParse({ ...valid, newPassword: 'short', confirmPassword: 'short' }),
      ),
    ).toBe('Use at least 8 characters');
  });
});

describe('credential change schemas', () => {
  it('ask for the current password only when the account has one', () => {
    expect(
      buildEmailChangeSchema(false).safeParse({ email: 'a@b.co', currentPassword: '' }).success,
    ).toBe(true);
    expect(
      firstError(buildEmailChangeSchema(true).safeParse({ email: 'a@b.co', currentPassword: '' })),
    ).toBe('Enter your password');

    const change = { currentPassword: '', newPassword: 'newpass99', confirmPassword: 'newpass99' };
    expect(buildPasswordChangeSchema(false).safeParse(change).success).toBe(true);
    expect(firstError(buildPasswordChangeSchema(true).safeParse(change))).toBe(
      'Enter your current password',
    );
  });
});
