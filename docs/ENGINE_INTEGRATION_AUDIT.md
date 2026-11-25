# Engine integration audit (Android)

This audit distinguishes a real embeddable integration from a provider's standalone Android app. A provider appearing in the UI does not mean its network is connected.

## Integration rules

- Use provider-published SDKs or supported integration interfaces only.
- Do not reverse-engineer private protocols, scrape credentials, embed another provider's account, or relay users through infrastructure we operate.
- Each user must authenticate with their own provider account when required.
- Respect provider free-tier quotas, supported regions, terms, and licenses.
- Never mark an engine as connected until its tunnel is established and traffic has been verified.

## Current findings

| Provider | Public Android integration path | Requirements / limitations | Decision |
|---|---|---|---|
| URnetwork | Official Android AAR SDK, package com.bringyour.sdk. | Uses URnetwork-hosted platform and an individual account JWT. Free plan has a daily data allowance; traffic stops when allowance is exhausted. Android VpnService and tunnel FD lifecycle remain the app's responsibility. SDK is MPL-2.0. | Implemented in app source; real-device verification pending |
| Lantern | Lantern Android library documentation describes liblantern.aar and sdk-debug.aar. | Must verify current release, supported API surface, licensing and Android compatibility before shipping. | Candidate; compatibility audit required |
| Geph | Official Android client source is public. | No separate, stable third-party Android embedding SDK has been confirmed in the reviewed docs. Reusing app internals needs license and maintenance review. | Do not label as integrated |
| Tor | Tor-Android library is explicitly described as embeddable; Orbot also uses IPtProxy transports. | Tor is a distinct anonymity network and has different performance/usage characteristics; it is not interchangeable with a conventional VPN provider. | Candidate after library/license audit |
| Psiphon | Official Android client source is public. | A supported third-party embedding SDK/API has not been confirmed. Reusing the full client requires source/license and integration review. | Do not label as integrated |
| Cloudflare WARP | Official WARP client documentation exists. | No public third-party Android embedding SDK was identified in the reviewed docs. Do not depend on private endpoints or undocumented protocols. | Not currently embeddable through a verified public SDK |
| Proton VPN | Official Android client source is public. | No supported third-party embedding SDK was identified. A public app repository is not itself permission or an API to embed the service. | Not currently embeddable through a verified public SDK |
| Windscribe | A supported public Android embedding SDK/API has not yet been verified. | Do not assume configuration files or private client protocols are available for third-party embedding. | Pending official integration evidence |

## Implementation order

1. URnetwork: code is integrated (official AAR, per-user instant-account flow, Android VPN permission/service lifecycle, disconnect/recovery, and fail-closed routing). Real-device traffic, quota/error presentation, and leak testing remain to be verified.
2. Tor: audit and integrate the official Tor-Android library if the supported API and license fit.
3. Lantern: verify current AAR and SDK contract, then integrate only if still maintained and permitted.
4. Geph and Psiphon: investigate upstream-supported embedding contracts and licenses before adapting client code.
5. WARP, Proton VPN, Windscribe: only proceed if their operators publish a supported third-party integration path.

## Acceptance checklist for every engine

- Clean Gradle build and dependency/license review.
- Explicit user consent and Android VPN permission.
- Correct foreground-service lifecycle and notification.
- Reliable connect, disconnect, reconnect, and process-death recovery.
- No false "connected" state before the tunnel is active.
- DNS/IPv4/IPv6 routing and leak checks.
- Traffic verified through the provider's documented test method.
- Free-tier quota, authentication, and provider errors shown accurately.
- No secrets, JWTs, or credentials written to logs.
