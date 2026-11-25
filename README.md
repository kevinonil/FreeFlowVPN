# FreeFlowVPN — development starter

Android / Jetpack Compose starter for a unified catalogue of network-provider integrations.

## Current scope
- Android application shell and engine catalogue UI.
- GitHub Actions workflow to build a debug APK and upload it as an artifact.

## Important
No VPN engine is integrated in this starter. The UI is not a working VPN client and does not establish a VPN connection. Provider integrations must use official SDKs/APIs and comply with each provider's terms and licenses. Free tiers may require accounts, impose quotas, or be unavailable in some regions.

## Build locally
Install JDK 17, Android SDK (API 35), and Gradle 8.9, then run:

    gradle --no-daemon assembleDebug

APK output:

    app/build/outputs/apk/debug/app-debug.apk
