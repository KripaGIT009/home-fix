import { friendlyErrorMessage, isApiError, isUnreachableError } from '@api/client';
import { AUTH_ERROR } from './constants';

/**
 * Customer copy for a failed email-auth request (sign-up, sign-in, codes,
 * reset, email change). The Auth Service writes its messages for the customer
 * ("That code is not right. 3 attempt(s) left."), so they are shown as they
 * are, with one exception: a failed email send answers 502, which the API
 * client — rightly, for every other 502 — turns into "can't reach HomeFix".
 */
export function emailAuthErrorMessage(error: unknown): string {
  if (isApiError(error) && error.code === AUTH_ERROR.EMAIL_DELIVERY_FAILED) {
    return 'We could not send the email. Please try again.';
  }
  return friendlyErrorMessage(error);
}

/** `warning` for "can't reach us" problems, `error` for a rejected request. */
export function emailAuthErrorSeverity(error: unknown): 'warning' | 'error' {
  if (isApiError(error) && error.code === AUTH_ERROR.EMAIL_DELIVERY_FAILED) return 'error';
  return isUnreachableError(error) ? 'warning' : 'error';
}
