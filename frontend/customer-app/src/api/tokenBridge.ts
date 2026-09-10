/**
 * Bridge between the auth store and the API client.
 *
 * The API client (an Axios instance) is created once at module load, but it
 * must read the *current* access token on every request. Rather than importing
 * the Zustand store directly (which would create a circular dependency: store
 * -> api -> store), the store registers accessor callbacks here.
 */

type TokenAccessor = () => string | null;
type UnauthorizedHandler = () => void;

let getAccessToken: TokenAccessor = () => null;
let onUnauthorized: UnauthorizedHandler = () => {};

/** Registered by the auth store on initialization. */
export function registerTokenAccessor(accessor: TokenAccessor): void {
  getAccessToken = accessor;
}

/** Registered by the auth store; called when the API sees a 401. */
export function registerUnauthorizedHandler(handler: UnauthorizedHandler): void {
  onUnauthorized = handler;
}

export function readAccessToken(): string | null {
  return getAccessToken();
}

export function handleUnauthorized(): void {
  onUnauthorized();
}
