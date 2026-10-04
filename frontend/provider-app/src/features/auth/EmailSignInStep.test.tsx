import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { ApiError } from '@api/client';
import type * as ApiModule from './api';
import { passwordLogin, resendEmailSignupCode } from './api';
import { EmailSignInStep } from './EmailSignInStep';
import { AUTH_ROUTES } from './navigation';

vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>();
  return { ...actual, passwordLogin: vi.fn(), resendEmailSignupCode: vi.fn() };
});

/** Stands in for the code screen: shows the router state it was opened with. */
function CodeScreenProbe() {
  const location = useLocation();
  return <pre data-testid="code-screen">{JSON.stringify(location.state)}</pre>;
}

function renderSignIn() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[AUTH_ROUTES.login]}>
        <Routes>
          <Route path={AUTH_ROUTES.login} element={<EmailSignInStep />} />
          <Route path={AUTH_ROUTES.verifyEmail} element={<CodeScreenProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function signIn(email = 'ravi@example.com', password = 'secret123') {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('Email'), email);
  await user.type(screen.getByLabelText('Password'), password);
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
  return user;
}

describe('EmailSignInStep', () => {
  it('sends the email as the identifier', async () => {
    vi.mocked(passwordLogin).mockRejectedValue(
      new ApiError({ status: 401, code: 'INVALID_CREDENTIALS', message: 'Incorrect.' }),
    );
    renderSignIn();
    await signIn();

    expect(await screen.findByText('Incorrect email or password.')).toBeInTheDocument();
    expect(vi.mocked(passwordLogin).mock.calls[0]?.[0]).toEqual({
      identifier: 'ravi@example.com',
      password: 'secret123',
    });
  });

  it('offers a new code for an unverified email and opens the code screen', async () => {
    vi.mocked(passwordLogin).mockRejectedValue(
      new ApiError({ status: 403, code: 'EMAIL_NOT_VERIFIED', message: 'Verify first.' }),
    );
    vi.mocked(resendEmailSignupCode).mockResolvedValue({
      status: 'CODE_SENT',
      expiresInSeconds: 600,
    });
    renderSignIn();
    const user = await signIn();

    await user.click(await screen.findByRole('button', { name: 'Send a new code' }));

    expect(vi.mocked(resendEmailSignupCode).mock.calls[0]?.[0]).toEqual({
      email: 'ravi@example.com',
    });
    const probe = await screen.findByTestId('code-screen');
    expect(JSON.parse(probe.textContent ?? '{}')).toEqual({
      email: 'ravi@example.com',
      resendInSeconds: 60,
    });
  });

  it('still opens the code screen when a code was sent under a minute ago', async () => {
    vi.mocked(passwordLogin).mockRejectedValue(
      new ApiError({ status: 403, code: 'EMAIL_NOT_VERIFIED', message: 'Verify first.' }),
    );
    vi.mocked(resendEmailSignupCode).mockRejectedValue(
      new ApiError({
        status: 429,
        code: 'TOO_MANY_REQUESTS',
        message: 'Too many requests.',
        retryAfterSeconds: 42,
      }),
    );
    renderSignIn();
    const user = await signIn();

    await user.click(await screen.findByRole('button', { name: 'Send a new code' }));

    const probe = await screen.findByTestId('code-screen');
    expect(JSON.parse(probe.textContent ?? '{}')).toMatchObject({ resendInSeconds: 42 });
  });

  it('counts down a lockout from Retry-After and holds the form', async () => {
    vi.mocked(passwordLogin).mockRejectedValue(
      new ApiError({
        status: 429,
        code: 'ACCOUNT_LOCKED',
        message: 'Too many failed sign-in attempts. Try again in 90 seconds.',
        retryAfterSeconds: 90,
      }),
    );
    renderSignIn();
    await signIn();

    expect(await screen.findByText(/Try again in 1:30/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled());
  });
});
