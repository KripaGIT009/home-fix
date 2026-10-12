import { describe, expect, it } from 'vitest';
import { readEmailCodeState, readForgotPasswordState, readLoginState } from './navigation';

describe('readLoginState', () => {
  it("keeps RequireAuth's `from` and the email-auth extras", () => {
    expect(
      readLoginState({
        from: { pathname: '/earnings', search: '' },
        signInMethod: 'email',
        email: 'ravi@example.com',
        notice: 'Password changed.',
      }),
    ).toEqual({
      from: { pathname: '/earnings' },
      signInMethod: 'email',
      email: 'ravi@example.com',
      notice: 'Password changed.',
    });
  });

  it('ignores missing or malformed state', () => {
    expect(readLoginState(null)).toEqual({});
    expect(readLoginState({ signInMethod: 'fax', email: 42, from: 'x' })).toEqual({});
  });
});

describe('readEmailCodeState', () => {
  it('needs an email', () => {
    expect(readEmailCodeState(undefined)).toBeNull();
    expect(readEmailCodeState({ email: '' })).toBeNull();
  });

  it('carries the sign-up details and the resend wait', () => {
    expect(
      readEmailCodeState({
        email: 'ravi@example.com',
        displayName: 'Ravi',
        mobileNumber: '+919876543210',
        resendInSeconds: 0,
      }),
    ).toEqual({
      email: 'ravi@example.com',
      displayName: 'Ravi',
      mobileNumber: '+919876543210',
      resendInSeconds: 0,
    });
    expect(readEmailCodeState({ email: 'a@b.co', resendInSeconds: -5 })).toEqual({
      email: 'a@b.co',
    });
  });
});

describe('readForgotPasswordState', () => {
  it('prefills the email when there is one', () => {
    expect(readForgotPasswordState({ email: 'a@b.co' })).toEqual({ email: 'a@b.co' });
    expect(readForgotPasswordState({ email: '' })).toEqual({});
    expect(readForgotPasswordState(null)).toEqual({});
  });
});
