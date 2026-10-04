import { brand } from '@lib/theme';
import type { PaymentMethod, RazorpayCheckout } from './api';

/**
 * Razorpay Checkout in the browser. The Payment Service creates the
 * Razorpay order for the booking's amount; this only opens Checkout on that
 * order and hands back what Checkout returns, which the server then verifies.
 * Nothing here decides how much is paid.
 */

const CHECKOUT_SCRIPT = 'https://checkout.razorpay.com/v1/checkout.js';

/** What Checkout returns for a successful payment. */
export interface RazorpaySuccess {
  razorpay_payment_id: string;
  razorpay_order_id: string;
  razorpay_signature: string;
}

/**
 * The customer closed Checkout without paying. `failure` is the last reason
 * Checkout gave if an attempt inside it failed first (card declined, ...).
 */
export class CheckoutDismissedError extends Error {
  readonly failure: string | undefined;

  constructor(failure?: string) {
    super(failure ?? 'Payment cancelled');
    this.name = 'CheckoutDismissedError';
    this.failure = failure;
  }
}

export function isCheckoutDismissed(error: unknown): error is CheckoutDismissedError {
  return error instanceof CheckoutDismissedError;
}

interface RazorpayInstance {
  open: () => void;
  on: (event: 'payment.failed', handler: (response: RazorpayFailure) => void) => void;
}

interface RazorpayFailure {
  error?: { description?: string };
}

declare global {
  interface Window {
    Razorpay?: new (options: Record<string, unknown>) => RazorpayInstance;
  }
}

let scriptLoading: Promise<void> | null = null;

/** Loads checkout.js once; a failed load can be retried on the next payment. */
function loadCheckoutScript(): Promise<void> {
  if (window.Razorpay) return Promise.resolve();
  if (!scriptLoading) {
    scriptLoading = new Promise<void>((resolve, reject) => {
      const script = document.createElement('script');
      script.src = CHECKOUT_SCRIPT;
      script.async = true;
      script.onload = () => resolve();
      script.onerror = () => {
        script.remove();
        scriptLoading = null;
        reject(new Error("We couldn't reach Razorpay. Check your connection and try again."));
      };
      document.head.appendChild(script);
    });
  }
  return scriptLoading;
}

/** The method the customer picked, as Checkout names it, to open on that tab. */
const CHECKOUT_METHOD: Partial<Record<PaymentMethod, string>> = {
  UPI: 'upi',
  CREDIT_DEBIT_CARD: 'card',
  NET_BANKING: 'netbanking',
  WALLET: 'wallet',
};

export interface CheckoutCustomer {
  name?: string | undefined;
  email?: string | undefined;
  contact?: string | undefined;
}

/**
 * Opens Checkout on the payment's Razorpay order. Resolves with Checkout's
 * success response; rejects with {@link CheckoutDismissedError} when the
 * customer closes it. A failed attempt inside Checkout does not reject:
 * Checkout lets the customer try again on the same order.
 */
export async function openRazorpayCheckout(
  checkout: RazorpayCheckout,
  method: PaymentMethod,
  customer: CheckoutCustomer,
): Promise<RazorpaySuccess> {
  await loadCheckoutScript();
  const Razorpay = window.Razorpay;
  if (!Razorpay) throw new Error("We couldn't open Razorpay. Please try again.");

  return new Promise<RazorpaySuccess>((resolve, reject) => {
    let lastFailure: string | undefined;
    const checkoutMethod = CHECKOUT_METHOD[method];
    const instance = new Razorpay({
      key: checkout.keyId,
      order_id: checkout.orderId,
      amount: checkout.amount,
      currency: checkout.currency,
      name: 'HomeFix',
      description: checkout.description ? `Booking ${checkout.description}` : 'Booking payment',
      prefill: {
        name: customer.name ?? '',
        email: customer.email ?? '',
        contact: customer.contact ?? '',
        ...(checkoutMethod ? { method: checkoutMethod } : {}),
      },
      theme: { color: brand.accent },
      handler: (response: RazorpaySuccess) => resolve(response),
      modal: {
        ondismiss: () => reject(new CheckoutDismissedError(lastFailure)),
      },
    });
    instance.on('payment.failed', (response) => {
      lastFailure = response.error?.description;
    });
    instance.open();
  });
}
