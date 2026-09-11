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
type SessionRefresher = () => Promise<string | null>;

let getAccessToken: TokenAccessor = () => null;
let onUnauthorized: UnauthorizedHandler = () => {};
let refreshSession: SessionRefresher = () => Promise.resolve(null);

/**
 * The refresh currently in flight, if any. Shared by every concurrent caller so
 * a burst of 401s rotates the (single-use) refresh token exactly once.
 */
let pendingRefresh: Promise<string | null> | null = null;

/** Registered by the auth store on initialization. */
export function registerTokenAccessor(accessor: TokenAccessor): void {
  getAccessToken = accessor;
}

/** Registered by the auth store; called when the API sees a 401. */
export function registerUnauthorizedHandler(handler: UnauthorizedHandler): void {
  onUnauthorized = handler;
}

/**
 * Registered by the auth store: exchanges the persisted refresh token for a
 * fresh access token. Must resolve to `null` rather than reject when the
 * session cannot be renewed.
 */
export function registerSessionRefresher(refresher: SessionRefresher): void {
  refreshSession = refresher;
}

export function readAccessToken(): string | null {
  return getAccessToken();
}

/**
 * Perform a silent refresh, collapsing concurrent callers onto one in-flight
 * request. Resolves to the new access token, or `null` when the session is over.
 */
export function refreshAccessToken(): Promise<string | null> {
  pendingRefresh ??= refreshSession().finally(() => {
    pendingRefresh = null;
  });
  return pendingRefresh;
}

export function handleUnauthorized(): void {
  onUnauthorized();
}
