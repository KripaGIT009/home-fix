import { Geolocation } from '@capacitor/geolocation';
import { env } from '@config/env';

/**
 * One way to follow the device's position, whatever the app runs in
 * (Requirement MT-14.3).
 *
 * In the native apps the position comes from `@capacitor/geolocation`, which
 * uses the platform's location service and asks for the OS permission itself
 * (the iOS prompt shows the Info.plist usage description). A WebView's
 * `navigator.geolocation` would also work on Android, but on iOS it prompts
 * once per launch with the app's local URL as the requester, and it stops
 * when the WebView is throttled. In a browser the plugin would only wrap
 * `navigator.geolocation` again, so the web app keeps using it directly and is
 * unchanged.
 */

/** Why no position is coming. */
export type LocationFailure = 'denied' | 'unavailable';

export interface PositionWatchHandlers {
  onPosition: (latitude: number, longitude: number) => void;
  onFailure: (failure: LocationFailure) => void;
}

const WATCH_OPTIONS = { enableHighAccuracy: true, maximumAge: 5_000, timeout: 20_000 } as const;

/** Error code the plugin reports for a refused location permission, on Android and iOS. */
const PLUGIN_PERMISSION_DENIED = 'OS-PLUG-GLOC-0003';

/**
 * Starts following the device's position and returns the function that stops
 * it. Failures are reported, never thrown: a provider without location can
 * still run the job, the customer just cannot see them on the map.
 */
export function watchDevicePosition(handlers: PositionWatchHandlers): () => void {
  return env.isNative ? watchNative(handlers) : watchBrowser(handlers);
}

function watchBrowser({ onPosition, onFailure }: PositionWatchHandlers): () => void {
  if (typeof navigator === 'undefined' || !navigator.geolocation) {
    onFailure('unavailable');
    return () => undefined;
  }
  const watchId = navigator.geolocation.watchPosition(
    (position) => onPosition(position.coords.latitude, position.coords.longitude),
    (error) => onFailure(error.code === error.PERMISSION_DENIED ? 'denied' : 'unavailable'),
    WATCH_OPTIONS,
  );
  return () => navigator.geolocation.clearWatch(watchId);
}

function watchNative({ onPosition, onFailure }: PositionWatchHandlers): () => void {
  let stopped = false;
  let watchId: string | null = null;

  const start = async () => {
    // Ask first, so a refusal is told apart from a missing fix. Android 12+
    // lets the user grant only approximate location; that is enough to show
    // the provider approaching, so either grant counts.
    let permission = await Geolocation.checkPermissions();
    if (permission.location !== 'granted' && permission.coarseLocation !== 'granted') {
      permission = await Geolocation.requestPermissions();
    }
    if (permission.location !== 'granted' && permission.coarseLocation !== 'granted') {
      if (!stopped) onFailure('denied');
      return;
    }
    if (stopped) return;
    const id = await Geolocation.watchPosition(WATCH_OPTIONS, (position, error) => {
      if (stopped) return;
      if (position) {
        onPosition(position.coords.latitude, position.coords.longitude);
      } else {
        onFailure(isPermissionError(error) ? 'denied' : 'unavailable');
      }
    });
    if (stopped) {
      void Geolocation.clearWatch({ id });
    } else {
      watchId = id;
    }
  };

  start().catch((error: unknown) => {
    // checkPermissions/requestPermissions throw when the device's location
    // services are switched off.
    if (!stopped) onFailure(isPermissionError(error) ? 'denied' : 'unavailable');
  });

  return () => {
    stopped = true;
    if (watchId !== null) void Geolocation.clearWatch({ id: watchId });
  };
}

function isPermissionError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false;
  const { code, message } = error as { code?: unknown; message?: unknown };
  return (
    code === PLUGIN_PERMISSION_DENIED ||
    (typeof message === 'string' && /permission.*denied/i.test(message))
  );
}
