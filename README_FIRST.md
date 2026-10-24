# Elevate Android WebView Wrapper with Native Google Sign-In

This is a replacement Android wrapper project for `https://notrade.myelevateapps.com` using a native Android WebView for the app UI and Google Play Services for Google account authentication. It keeps the existing package ID `com.elevate.optiontrading`.

## Why Google sign-in is native
Google blocks/limits Google Identity Services sign-in inside embedded WebViews. This wrapper intercepts the existing Elevate Welcome-page Google buttons, opens the native Google account chooser, obtains an ID token, and submits that token to Elevate's existing server endpoints. The WebView remains the main app UI. It does not embed Google's sign-in page inside the WebView.

## Required configuration before building

1. In Elevate Admin Settings, find the Google OAuth **Web application Client ID** configured for Elevate.
2. Confirm `app/src/main/res/values/strings.xml` contains that exact Web Client ID; it is used as the ID-token audience.
3. In Google Cloud Console, ensure there is an Android OAuth client for package `com.elevate.optiontrading` and the SHA-1 signing fingerprint used by the APK. Debug and release signing certificates may have different SHA-1 fingerprints.
4. Commit the contents of this folder to a new branch in `atanubhatttacharyya/twa_android_wrapper` (or build from a separate branch/repository). The Codemagic workflow can use the existing Gradle build configuration; build with `gradle --no-daemon assembleDebug`.
5. Install and test the new APK. Do not overwrite the working APK until native Google sign-in, login, registration, account modification, session handling, and sign-out have been tested.

## Important implementation notes

- Website: `https://notrade.myelevateapps.com`
- Android application ID: `com.elevate.optiontrading`
- App label: `Elevate Option Trading`
- Minimum SDK: 23; compile/target SDK: 35; Java 17.
- The wrapper calls the existing server endpoints `/login`, `/auth/google/register`, `/auth/google/modify/verify`, and `/auth/google/modify/update` using the Google ID token. It transfers the returned Elevate session cookie into the WebView for login.
- The wrapper does not change AWS or Flask code.
- Google OAuth configuration and Android signing fingerprints must be correct for the native Google sign-in flow to work.
- This source ZIP has not been built in Codemagic or tested on a physical phone in this environment. Test it on a separate build before replacing the current app.

## Google authentication blocker (must be completed before a working APK)

Google authentication requires the Web Client ID in `strings.xml` to match the Web application Client ID configured on the Elevate backend, plus an Android OAuth client registered for package `com.elevate.optiontrading` and the APK signing certificate SHA-1. A wrong or missing Android package/fingerprint commonly causes account selection to fail with a developer configuration error. The app displays sign-in errors in both the login status area and an Android toast. For `DEVELOPER_ERROR`, it also displays the installed APK's SHA-1 so the exact signing certificate can be registered in Google Cloud Console. Failures after Google returns to the app are differentiated as TLS, DNS, timeout, network I/O, response, or unexpected runtime errors. Unexpected runtime errors include the failing operation and a shortened exception detail directly in the app; full details are also recorded under the `ElevateAuth` Android log tag. This is an OAuth/network configuration dependency, not a WebView permission issue.

The native bridge validates the configured ID before attempting sign-in and handles devices where Google Play Services sign-in cannot be launched. Login failures, including `DEVELOPER_ERROR`, are displayed in the login status area. This source change must be included in a newly built APK before testing the updated error feedback.
