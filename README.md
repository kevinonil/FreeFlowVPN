# FreeFlowVPN

Android VPN client built with Kotlin, Jetpack Compose, and the official URnetwork Android SDK.

## Implemented
- Android VPN permission flow and foreground VPN service.
- URnetwork account creation through its documented instant-account endpoint.
- Recovery phrase shown after account creation; JWT and recovery material are encrypted with Android Keystore-backed AES-GCM storage.
- URnetwork SDK device initialization, best-available connect location, Android TUN interface, and SDK I/O loop.
- Disconnect handling and fail-closed route-local setting.
- GitHub Actions downloads a pinned SDK release, verifies its SHA-256, builds the APK, and uploads it as an artifact.

## Current engine status
- **URnetwork:** implemented in the Android app. A successful CI build verifies compilation and packaging, not real-device connectivity.
- **Lantern, Geph, Tor, Psiphon, Cloudflare WARP, Proton VPN, Windscribe:** listed in the UI but intentionally disabled until a supported, compatible integration path is implemented and tested. A standalone Android app or public source repository is not automatically an embeddable SDK.

## Important limitations
- URnetwork free-tier allowance and availability are controlled by URnetwork.
- Every user creates and uses their own URnetwork account. No shared credentials or private VPN infrastructure are included.
- The recovery phrase is shown once after account creation. The user must save it securely before dismissing the dialog.
- The app has not yet undergone real-device, traffic-routing, DNS/IPv6 leak, reconnect, or battery/background testing.
- Do not treat a green GitHub build as proof that the live VPN tunnel works on every Android device.

## Build
The GitHub Actions workflow downloads the pinned URnetwork AAR into `app/libs`, verifies its checksum, then runs:

    gradle --no-daemon assembleDebug

The APK is uploaded as the `FreeFlowVPN-debug` workflow artifact.
