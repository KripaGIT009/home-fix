# Mobile builds (Capacitor)

The Customer App and the Provider App ship as installable Android and iOS apps.
The Admin Portal does not: it is a desktop console and stays a web app.

| App | Directory | App id | App name |
| --- | --- | --- | --- |
| Customer | `frontend/customer-app` | `com.homefix.customer` | HomeFix |
| Provider | `frontend/provider-app` | `com.homefix.provider` | HomeFix Pro |

## What Capacitor gives us, and why we wrap instead of rewriting

Capacitor packages an existing web build as a native app. The production bundle
from `npm run build` is copied into a native project and served to a full-screen
system WebView from the app's own local origin; a thin native bridge exposes
device APIs to JavaScript. The result installs from an APK/AAB or the App Store
and behaves like any other app on the device.

We wrap rather than rewrite because the two apps are already complete, tested
React applications, and a React Native or Flutter port would mean maintaining a
second implementation of every screen, form, validation rule and API binding for
the same product. Wrapping keeps one codebase, one lint/typecheck/build pipeline
and one place to fix a bug, and the web app remains deployable as a web app. The
cost is that anything genuinely native has to come through a Capacitor plugin,
and that the WebView is the performance ceiling — acceptable for a
forms-and-lists product, and revisitable per screen later, since Capacitor lets
native code and the WebView coexist.

What the wrapper does **not** change: routing, state, styling and API calls are
exactly the web app's. The one thing that must change is the API base URL —
see [Pointing the app at a backend](#pointing-the-app-at-a-backend).

## What is in the repo

Per mobile app:

- `capacitor.config.ts` — app id, app name, `webDir: 'dist'`.
- `android/` — the generated native Android project, committed. Build outputs
  and the copied web bundle are ignored via the generated `android/.gitignore`.
- `android/app/src/debug/AndroidManifest.xml` — debug-only cleartext override
  (see [Cleartext traffic](#cleartext-traffic-local-development-only)).
- npm scripts: `mobile:sync`, `mobile:sync:android`, `mobile:open:android`,
  `mobile:run:android`.
- Dependencies: `@capacitor/core`, `@capacitor/android`, `@capacitor/ios`;
  `@capacitor/cli` as a devDependency, which is how Capacitor's own docs split
  them.

There is no `ios/` directory. It is generated on a Mac with one command — see
[iOS](#ios-macos-only). Nothing else about the iOS setup is missing.

## Prerequisites

| Tool | Version | Needed for |
| --- | --- | --- |
| Node.js | **≥ 20.0.0** | Capacitor 7 CLI (pinned across all four `@capacitor/*` packages) |
| JDK | **21** | Android Gradle Plugin used by Capacitor 7 |
| Android Studio | Ladybug (2024.2) or newer | Android SDK, emulator, `adb` |
| Android SDK | Platform 35 (compileSdk/targetSdk 35, minSdk 23) | Android builds |
| Xcode | 15 or newer, plus CocoaPods | iOS builds (macOS only) |

Capacitor is pinned to **7.x** deliberately: Capacitor 8 requires Node ≥ 22, and
this project was set up on Node 20. Once everyone is on Node 22+, bumping all
four `@capacitor/*` packages to 8 together is the upgrade — keep the CLI and the
runtime on the same major or `cap sync` and the native project will disagree.

Android Studio must know where the SDK is. If `cap run android` cannot find it,
set `ANDROID_HOME` (Windows: `%LOCALAPPDATA%\Android\Sdk`) and add
`platform-tools` to `PATH`.

## Build and run on Android

Everything below runs from the app directory (`frontend/customer-app` or
`frontend/provider-app`).

```bash
# 1. Configure the backend URLs for a native build first (see next section),
#    because they are baked into the bundle at build time.
cp .env.example .env      # then edit the native section

# 2. Build the web app and copy it into the native project.
npm run mobile:sync

# 3a. Open Android Studio and press Run.
npm run mobile:open:android

# 3b. Or build, install and launch from the terminal.
npm run mobile:run:android          # prompts for a target if several exist
npx cap run android --list          # list available emulators/devices
npx cap run android --target <id>   # pick one
```

`mobile:sync` is `npm run build && cap sync`: it rebuilds `dist`, copies it into
`android/app/src/main/assets/public`, and updates the native dependency list.
**Re-run it after every web change** — the native project holds a *copy* of the
bundle, so editing `src/` and pressing Run in Android Studio rebuilds the old
assets.

### Emulator

Start an AVD from Android Studio's Device Manager (or `emulator -list-avds` then
`emulator -avd <name>`), then `npm run mobile:run:android`.

### Physical device

1. Enable Developer options → USB debugging on the device, connect by USB, and
   accept the debugging prompt.
2. `adb devices` should list it.
3. `npm run mobile:run:android`.

To inspect the WebView, open `chrome://inspect` in desktop Chrome while the app
is running.

## Pointing the app at a backend

In the browser the apps use a relative base URL (`/api`) and nginx or the Vite
dev server proxies it onward — `/api/auth/**` to the Auth Service, everything
else to the API Gateway. **A native app has neither a proxy nor a meaningful
origin**: the bundle is served from the app's own local host, so `/api/bookings`
resolves to the WebView itself and every request fails against a server that
does not exist.

So a native build must set absolute URLs, and needs the Auth Service separately
because it does not sit behind the gateway:

| Variable | Points at | Port |
| --- | --- | --- |
| `VITE_API_BASE_URL` | API Gateway | 8080 |
| `VITE_AUTH_BASE_URL` | Auth Service | 8081 |
| `VITE_REALTIME_BASE_URL` | SSE/WebSocket origin (customer app only) | 8080 |

Addresses that work from a device:

- **Android emulator** — `10.0.2.2` is the emulator's alias for the host
  machine's loopback. `localhost` means the emulator itself.
  ```
  VITE_API_BASE_URL=http://10.0.2.2:8080
  VITE_AUTH_BASE_URL=http://10.0.2.2:8081
  VITE_REALTIME_BASE_URL=http://10.0.2.2:8080
  ```
  (Genymotion uses `10.0.3.2`.)
- **Physical device on the same Wi-Fi** — the host's LAN address from `ipconfig`
  / `ip addr`, e.g. `http://192.168.1.20:8080`. The backend must listen on all
  interfaces, not just loopback, and the host firewall must allow the ports.
- **Physical device over USB** — `adb reverse tcp:8080 tcp:8080` and
  `adb reverse tcp:8081 tcp:8081` forward the device's `localhost` to the host,
  so `http://localhost:8080` works with no LAN exposure. Re-run after replugging.

These are read at **build** time, so change `.env` → `npm run mobile:sync` →
run again.

If a native build starts with a relative URL, it refuses to run and shows the
offending variable names on screen (`assertRuntimeConfig` in `src/config/env.ts`,
rendered by `src/main.tsx`) rather than issuing requests to an origin that is not
there.

## Cleartext traffic (local development only)

Local backends are plaintext `http://`, and Android has blocked cleartext by
default since API 28. Each app therefore carries a **debug-variant** manifest:

```xml
<!-- android/app/src/debug/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:usesCleartextTraffic="true" />
</manifest>
```

The manifest merger applies it to debug builds only, so `assembleRelease` never
sees it. **Do not move this attribute into `android/app/src/main/AndroidManifest.xml`** —
there it would ship in the release APK and silently permit unencrypted traffic
for every user.

If the WebView still blocks the calls as mixed content (the bundle is served over
the app's `https` scheme while the API is `http`), add this to
`capacitor.config.ts` for local work only, and remove it before releasing:

```ts
server: { cleartext: true },
```

Neither switch is needed once the backend is reachable over HTTPS.

## Permissions

### Android

`android/app/src/main/AndroidManifest.xml` declares:

- `INTERNET` — both apps (added by Capacitor).
- `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION` — **customer app only**.
  The address form and live tracking read the device position through the
  WebView's `navigator.geolocation`, which Android grants only if these are
  declared. Both are listed because Android 12+ lets the user downgrade a
  location grant to "approximate", and a build declaring only `FINE` cannot
  accept that choice.

Android also asks the user at runtime the first time location is read; the
WebView surfaces that prompt automatically.

### iOS

`Info.plist` needs a usage description for every permission, and the App Store
rejects builds that omit them. For the **customer app**, add:

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>HomeFix uses your location to set your service address and show your professional's live position on the way to you.</string>
```

Add `NSLocationAlwaysAndWhenInUseUsageDescription` only if tracking ever has to
continue with the app in the background — it invites extra App Review scrutiny,
so do not add it speculatively.

The provider app needs no location key today.

## iOS (macOS only)

The iOS project is not in the repo because it cannot be generated meaningfully on
Windows. On a Mac, from the app directory:

```bash
npx cap add ios          # one time; creates ios/ and runs pod install
npm run mobile:sync      # build + copy, same as Android
npx cap open ios         # opens the workspace in Xcode, then press Run
```

Then, in Xcode:

1. Select the app target → **Signing & Capabilities**, choose your team; Xcode
   manages the provisioning profile.
2. Add the usage-description keys above to `Info.plist`.
3. For a local plaintext backend, add a development-only App Transport Security
   exception to `Info.plist` and **remove it before shipping**:
   ```xml
   <key>NSAppTransportSecurity</key>
   <dict>
     <key>NSAllowsLocalNetworking</key><true/>
   </dict>
   ```
   `NSAllowsLocalNetworking` covers local/LAN hosts without the blanket
   `NSAllowsArbitraryLoads`, which App Review questions.
4. On the iOS simulator the host is reachable as `localhost` (not `10.0.2.2`);
   a physical iPhone needs the host's LAN address.

## Release checklist

Web/config:

- [ ] `VITE_API_BASE_URL`, `VITE_AUTH_BASE_URL` and (customer)
      `VITE_REALTIME_BASE_URL` point at production **`https://`** origins. The
      values are compiled into the bundle — rebuild after changing them.
- [ ] `VITE_GOOGLE_CLIENT_ID` matches the Auth Service's `GOOGLE_CLIENT_ID`.
- [ ] `npm run lint`, `npm run typecheck`, `npm run build` are green, then
      `npm run mobile:sync` so the native project carries the final bundle.

Android:

- [ ] No `usesCleartextTraffic` in `app/src/main/AndroidManifest.xml`; the debug
      overlay stays debug-only.
- [ ] No `server` block (`cleartext`, `url`) left in `capacitor.config.ts`.
- [ ] `versionCode` incremented and `versionName` set in `android/app/build.gradle`.
- [ ] Release signing configured with a keystore kept **outside** the repo.
- [ ] Build the bundle: `cd android && ./gradlew bundleRelease`.
- [ ] Install the release build on a real device and confirm sign-in, booking
      and live tracking work against the production API.

iOS:

- [ ] No ATS exception left in `Info.plist`.
- [ ] Usage-description strings are the real, user-facing wording.
- [ ] Version and build number bumped; archive and upload from Xcode.

## Known gaps

- The Android project was generated and configured on a Windows machine with no
  Android SDK or JDK, so **no Gradle build has been run and no APK produced
  here**. `npm run mobile:sync` and everything upstream of Gradle is verified;
  the first `./gradlew` run on a machine with the SDK is the next check.
- The iOS project has never been generated (`npx cap add ios` needs macOS).
  The dependency is installed and the steps above are the whole procedure.
- Both apps use the WebView's `navigator.geolocation` rather than the
  `@capacitor/geolocation` plugin. That works with the manifest permissions
  above; move to the plugin if background or high-accuracy tracking is needed.
