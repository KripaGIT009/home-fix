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
- `ios/` — the generated native iOS project (`ios/App/App.xcodeproj`,
  `App.xcworkspace`, `Podfile`, `App/Info.plist` with the usage descriptions).
  It is meant to be committed like `android/`; as of 2026-10-03 it is still
  untracked in the working tree. `Pods/`, build output, the copied web bundle
  (`App/App/public`), the generated `capacitor.config.json`/`config.xml` and
  `capacitor-cordova-ios-plugins/` are ignored via the generated `ios/.gitignore`.
- npm scripts: `mobile:sync` (both platforms), `mobile:sync:android`,
  `mobile:open:android`, `mobile:run:android`, `mobile:sync:ios`,
  `mobile:open:ios`.
- Dependencies: `@capacitor/core`, `@capacitor/android`, `@capacitor/ios`;
  `@capacitor/cli` as a devDependency, which is how Capacitor's own docs split
  them. The provider app also has `@capacitor/geolocation` (see
  [Plugins](#plugins)).

The `ios/` projects were generated on Windows with `npx cap add ios`, which
writes the Xcode project but cannot run `pod install` (no CocoaPods, no
`xcodebuild`). On Windows or Linux, `cap sync` (and therefore `mobile:sync`)
still copies the bundle into `ios/`, but logs "Skipping pod install because
CocoaPods is not installed". The first `npx cap sync ios` on a Mac installs the
pods — see [iOS](#ios-macos-only).

## Prerequisites

| Tool | Version | Needed for |
| --- | --- | --- |
| Node.js | **≥ 20.0.0** | Capacitor 7 CLI (pinned across all four `@capacitor/*` packages) |
| JDK | **21** (17 also works) | Android Gradle Plugin 8.7.2 / Gradle 8.11.1 (`android/build.gradle`, `gradle-wrapper.properties`); Gradle 8.11 cannot run on newer JDKs such as 25 |
| Android Studio | Ladybug (2024.2) or newer | Android SDK, emulator, `adb` (bundles a suitable JDK) |
| Android SDK | Platform 35 (compileSdk/targetSdk 35, minSdk 23) | Android builds |
| macOS + Xcode | **Xcode 16** or newer (iOS deployment target 14.0) | iOS builds; an iOS app cannot be built or signed on Windows or Linux |
| CocoaPods | 1.13 or newer (`sudo gem install cocoapods` or `brew install cocoapods`) | Installs the Capacitor pods into `ios/App` |
| Apple account | Free Apple ID for your own device; paid Apple Developer Program for TestFlight / App Store | iOS signing |

Capacitor is pinned to **7.x** deliberately: Capacitor 8 requires Node ≥ 22, and
this project was set up on Node 20. Once everyone is on Node 22+, bumping all
four `@capacitor/*` packages to 8 together is the upgrade — keep the CLI and the
runtime on the same major or `cap sync` and the native project will disagree.

Android Studio must know where the SDK is. If `cap run android` cannot find it,
set `ANDROID_HOME` (Windows: `%LOCALAPPDATA%\Android\Sdk`) and add
`platform-tools` to `PATH`. For a terminal Gradle build, point `JAVA_HOME` at a
JDK 21 (Android Studio's own lives in `<Android Studio>/jbr`).

To build a debug APK without Android Studio:

```bash
npm run mobile:sync:android
cd android && ./gradlew assembleDebug      # Windows: gradlew.bat assembleDebug
# -> android/app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Build and run on Android

Everything below runs from the app directory (`frontend/customer-app` or
`frontend/provider-app`).

```bash
# 1. Configure the backend URLs for a native build first (see next section),
#    because they are baked into the bundle at build time.
cp .env.example .env      # then edit the native section

# 2. Build the web app and copy it into the native project.
npm run mobile:sync:android         # or mobile:sync for Android and iOS together

# 3a. Open Android Studio and press Run.
npm run mobile:open:android

# 3b. Or build, install and launch from the terminal.
npm run mobile:run:android          # prompts for a target if several exist
npx cap run android --list          # list available emulators/devices
npx cap run android --target <id>   # pick one
```

`mobile:sync` is `npm run build && cap sync` (`mobile:sync:android` limits the
sync to Android): it rebuilds `dist`, copies it into
`android/app/src/main/assets/public` (and `ios/App/App/public`), and updates the
native dependency list.
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

  ```dotenv
  VITE_API_BASE_URL=http://10.0.2.2:8080
  VITE_AUTH_BASE_URL=http://10.0.2.2:8081
  VITE_REALTIME_BASE_URL=http://10.0.2.2:8080   # customer app only
  ```

  (Genymotion uses `10.0.3.2`.) The local Compose stack publishes the gateway
  and Auth Service as `"8080:8080"` and `"8081:8081"`, i.e. on all interfaces.
- **iOS simulator** — the simulator shares the Mac's network stack, so
  `http://localhost:8080` / `http://localhost:8081` reach a backend on the same
  Mac (not `10.0.2.2`).
- **Physical device (Android or iPhone) on the same Wi-Fi** — the host's LAN
  address, e.g. `http://192.168.1.20:8080`:
  - find it with `ipconfig` (Windows, "IPv4 Address" of the Wi-Fi adapter),
    `ipconfig getifaddr en0` (macOS) or `ip addr` (Linux);
  - the backend must listen on all interfaces, not just loopback. A Docker
    Compose mapping such as `"8080:8080"` publishes on `0.0.0.0`, but a
    `127.0.0.1:8080:8080` mapping or a service bound to `localhost` is not
    reachable from the phone;
  - the host firewall must allow inbound TCP 8080 and 8081 (Windows: accept the
    firewall prompt, or run `New-NetFirewallRule -DisplayName HomeFix
    -Direction Inbound -Protocol TCP -LocalPort 8080,8081 -Action Allow
    -Profile Private` with the Wi-Fi network set to *Private*);
  - check from the phone's browser first: `http://192.168.1.20:8080/` should
    answer (even with a 401/404) before you suspect the app;
  - guest or office Wi-Fi often isolates clients from each other; use a phone
    hotspot or USB instead;
  - a DHCP lease can change the address; rebuild with the new one
    (`npm run mobile:sync`) when it does. On a Mac, the Bonjour name
    (`http://<mac-name>.local:8080`, see *System Settings → General → Sharing*)
    survives address changes and suits the iOS ATS rule below.
- **Physical device over USB** — `adb reverse tcp:8080 tcp:8080` and
  `adb reverse tcp:8081 tcp:8081` forward the device's `localhost` to the host,
  so `http://localhost:8080` works with no LAN exposure. Re-run after replugging.

These are read at **build** time, so change `.env` → `npm run mobile:sync` →
run again. Sign-in calls the Auth Service at `VITE_AUTH_BASE_URL` directly, so
both variables must use a host the device can reach.

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

### iOS: App Transport Security

iOS blocks plaintext `http://` loads through App Transport Security (ATS). The
committed `Info.plist` deliberately has **no** ATS exception, so a release build
cannot ship one by accident. For local work add this to
`ios/App/App/Info.plist`, and do not commit it:

```xml
<key>NSAppTransportSecurity</key>
<dict>
  <key>NSAllowsLocalNetworking</key><true/>
</dict>
```

`NSAllowsLocalNetworking` exempts `localhost`, unqualified host names and
`*.local` names, which covers the simulator (`http://localhost:8080`) and a
device pointed at the Mac's Bonjour name (`http://<mac-name>.local:8080`). It
does not cover a numeric address such as `http://192.168.1.20:8080`; for that,
use the `.local` name, or as a last resort temporarily set
`<key>NSAllowsArbitraryLoads</key><true/>` instead (App Review rejects builds
that keep it without a justification). Either way, remove the block before
archiving (see the release checklist).

## Plugins

| Plugin | App | Used for |
| --- | --- | --- |
| `@capacitor/geolocation` 7.x | Provider | Sharing the provider's position while on the way to a job (`useShareLocation` → `src/features/jobs/deviceLocation.ts`). In the native apps it asks for the OS location permission and watches the platform location service; in a browser the same hook uses `navigator.geolocation`, so the web app is unchanged. A refusal shows the same "location permission is off" notice on every platform. |

Keep every `@capacitor/*` package on the same major as `@capacitor/core` (7).
After adding a plugin run `npx cap sync`: it registers the plugin in
`android/capacitor.settings.gradle` / `android/app/capacitor.build.gradle` and
in `ios/App/Podfile`.

Photos need no plugin. The job photo card (provider) and the booking media
picker (customer) are `<input type="file">` controls, which the native
WebViews support. In the native apps each also shows a camera button
(**Take photo** in the provider app, **Take a photo** in the customer app): a
second file input marked `capture="environment"`, which makes Android's WebView
launch the camera (Android's plain file chooser offers only the gallery) and
opens the camera directly on iOS. The web build hides that button, because
`capture` would take the gallery choice away from mobile browsers. If the
capture UX ever needs more (cropping, compression, several shots in a row),
`@capacitor/camera` 7.x is the upgrade path.

The customer app reads the device position in one place only: "use my current
location" on the service-request address form, a one-shot
`navigator.geolocation.getCurrentPosition` in the WebView. That works with the
permissions below and needs no plugin. Live tracking does **not** read the
customer's position: the map shows the provider's position from the Location
Service (snapshot, then SSE) and the service address stored on the booking.

## Permissions

### Android

`android/app/src/main/AndroidManifest.xml` declares:

| Permission | App | Why |
| --- | --- | --- |
| `INTERNET` | both | API calls (added by Capacitor) |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` | both | Customer: "use my current location" on the address form. Provider: sharing the position while on the way (`PROVIDER_ON_THE_WAY`). Both are listed because Android 12+ lets the user downgrade a grant to "approximate", and a build declaring only `FINE` cannot accept that choice. |
| `CAMERA`, plus `uses-feature android.hardware.camera` with `required="false"` | both | Taking job photos (provider) and booking photos (customer). Capacitor's WebView asks for it at runtime only because it is declared. The feature is optional so devices without a camera can still install the app. |

Android asks at runtime the first time location or the camera is used. The
provider app explains the location prompt on the Active Job screen ("Allow
location access if asked…") just before it appears.

Picking from the gallery needs no storage permission: the system picker grants
access to the chosen files only.

### iOS

`ios/App/App/Info.plist` carries a usage description for every permission the
apps use. iOS shows it in the permission prompt, and the App Store rejects
builds that omit one:

| Key | Customer | Provider |
| --- | --- | --- |
| `NSLocationWhenInUseUsageDescription` | "use my current location" on the address form | sharing the position while on the way |
| `NSCameraUsageDescription` | photographing the problem when booking | before/after job photos |
| `NSPhotoLibraryUsageDescription` | attaching photos/videos from the library | attaching job photos from the library |

The committed customer string currently reads "…to set your service address and
to show your professional on the way to you". The second half does not need the
device's location (see [Plugins](#plugins)); trim it to the address use before
a release, since App Review expects the string to match what the app does. The
Android manifest comment in the customer app makes the same over-claim.

Add `NSLocationAlwaysAndWhenInUseUsageDescription` only if tracking ever has to
continue with the app in the background — it invites extra App Review scrutiny,
so do not add it speculatively. Edit the strings in `Info.plist` (or Xcode's
*Info* tab); `npx cap sync` does not overwrite them.

## iOS (macOS only)

Building, running and signing an iOS app needs **macOS with Xcode**: Apple
ships the iOS SDK, simulator and code-signing tools only for macOS. The `ios/`
projects are in the repo, so a Mac user starts from a clone:

```bash
# one time per machine
xcode-select --install            # command-line tools, if Xcode was never opened
sudo gem install cocoapods        # or: brew install cocoapods

# per app (frontend/customer-app or frontend/provider-app)
npm ci
cp .env.example .env              # set the native URLs, see "Pointing the app at a backend"
npm run mobile:sync:ios           # build + copy the bundle + pod install
npm run mobile:open:ios           # opens ios/App/App.xcworkspace in Xcode
```

Open the **`.xcworkspace`**, not the `.xcodeproj`: only the workspace sees the
pods. If `pod install` fails on Apple Silicon with an `ffi` error, reinstall
CocoaPods with Homebrew, or run `cd ios/App && arch -x86_64 pod install` once.

### Simulator

In Xcode pick an iPhone simulator and press Run, or from the terminal:

```bash
npx cap run ios --list            # simulators and connected devices
npx cap run ios --target <id>
```

The simulator reaches a backend on the same Mac at `http://localhost:8080` /
`:8081` (with the ATS exception from
[iOS: App Transport Security](#ios-app-transport-security)). The simulator has
no camera: test photo picking with the library, and camera capture on a device.
Simulate a location from *Features → Location* in the Simulator menu.

### Physical iPhone

1. Connect the iPhone by USB (or pair it over Wi-Fi in *Window → Devices and
   Simulators*) and trust the Mac. On iOS 16+ enable *Settings → Privacy &
   Security → Developer Mode* and restart the phone.
2. In Xcode select the **App** target → *Signing & Capabilities*, tick
   *Automatically manage signing* and pick your **Team**. A free Apple ID works
   for your own device (the app expires after 7 days and must be re-run); the
   paid Apple Developer Program is needed for TestFlight and the App Store. If
   the bundle id is taken in your team, change it for local work only; the
   committed ids are `com.homefix.customer` and `com.homefix.provider`.
3. Select the phone as the run destination and press Run. With a free account,
   the first launch is blocked until you trust the developer under *Settings →
   General → VPN & Device Management*.
4. Point the build at the Mac's LAN address or Bonjour name (see
   [Pointing the app at a backend](#pointing-the-app-at-a-backend)) and add the
   development ATS exception.

### Debugging

Enable *Settings → Safari → Advanced → Web Inspector* on the device, then in
Safari on the Mac use *Develop → (device or simulator) → (app)* to get the
console, network panel and DOM of the WebView. Native logs (plugin errors,
permission results) are in Xcode's console. On Android the equivalents are
`chrome://inspect` and Logcat in Android Studio.

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

- [ ] No `NSAppTransportSecurity` exception left in `Info.plist`.
- [ ] Usage-description strings are the real, user-facing wording.
- [ ] Version and build number bumped; archive and upload from Xcode.

## Known gaps

- **No native build has been run from this repository yet.** The Android and
  iOS projects were generated and configured on the Windows development
  machine, which cannot build either:

  | Check (2026-10-03) | Found | Consequence |
  | --- | --- | --- |
  | Android SDK | none: `ANDROID_HOME` unset, no `%LOCALAPPDATA%\Android\Sdk`, no Android Studio | `./gradlew assembleDebug` cannot run |
  | JDK | 25.0.2 is the only one installed | too new for Gradle 8.11.1; install JDK 17 or 21 (or use Android Studio's bundled JBR) and point `JAVA_HOME` at it |
  | macOS / Xcode / CocoaPods | none (Windows 11) | no `pod install`, no iOS build, signing or simulator; iOS needs a Mac |
  | Node.js | 20.15.0 | fine for Capacitor 7 |

  Verified there: `npm run build` and `npx cap sync` for both platforms (the
  bundle is in `android/app/src/main/assets/public` and `ios/App/App/public`,
  synced 2026-10-03), plus typecheck and lint. The next checks are the first
  Gradle build on a machine with the SDK and a JDK 17/21, and the first
  `pod install` + Xcode build on a Mac.
- `ios/` is not committed yet (untracked as of 2026-10-03); commit both apps'
  `ios/` folders together with the rest of the iOS work.
- `npx cap add ios` ran without CocoaPods, so `ios/App/Podfile.lock` does not
  exist yet. The first `npx cap sync ios` on a Mac creates it; commit it then so
  every Mac resolves the same pod versions.
- The customer app uses the WebView's `navigator.geolocation` rather than
  `@capacitor/geolocation`. On iOS the WebView can add its own prompt naming the
  app's local origin after the system one; move the customer app to the plugin
  too if that wording matters or background tracking is ever needed.
