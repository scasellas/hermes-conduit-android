# Conduit for Android

A native Android client for [Hermes Agent](https://github.com/NousResearch/hermes-agent), ported from the iOS app in this repository. It is written in Kotlin with Jetpack Compose and talks to the same self-hosted Hermes dashboard. There is no relay and no extra server-side setup.

> **Status: early port.** The core chat experience works. The many iOS-only surfaces (see below) have not been ported yet.

## What's included

- **Sign-in**
  - Dashboard address with password sign-in.
  - Optional Cloudflare Access service token.
  - Credentials and the session cookie are stored in Keystore-backed encrypted preferences.
  - Plain HTTP follows the iOS policy: it is allowed only for localhost, private LAN (RFC1918) and Tailscale (`*.ts.net`, `100.64.0.0/10`).
- **Chats**
  - The session list for the active profile, with pinned chats first.
  - A refresh button to reload the list.
  - Rename, pin, archive and delete, using the dashboard's mutation-identity protocol.
- **Profiles:** switch between Hermes profiles.
- **Streaming chat**
  - Live Markdown responses, reasoning traces and tool-call cards with inputs and outputs.
  - Stop a running turn. A message sent while a turn runs is steered or queued, as the gateway decides.
  - "Load earlier" pages back through long transcripts.
- **Decisions**
  - Inline approval cards: allow once, for the chat, always, or deny.
  - Clarify questions, including multiple-choice and batched questions.
- **Models:** pick a provider and model for the current chat, including the gateway's expensive-model confirmation.
- **Resilience**
  - JSON-RPC keepalive pings.
  - Automatic reconnect with backoff and a fresh single-use ticket.
  - Silent re-login with the saved password when the dashboard session expires.
- **Layout:** phones and tablets. On wide screens the chat list stays docked.

## Not yet ported from iOS

- Voice: push-to-talk, GPT-Live, Gemini Live and Grok Live.
- Push notifications. These need an FCM path in the notifier relay.
- Native OAuth (PKCE) sign-in. Dashboards that only offer browser sign-in show an explanatory error.
- Attachments.
- Bot Mode, group chats, Kanban, the Capabilities tab, scheduled jobs, gateway logs and restart.
- Multiple saved dashboards, biometric lock, widgets and localization.

The CarPlay, Apple Watch, Live Activities, HealthKit and Siri Shortcuts features have Android counterparts (Android Auto, Wear OS, ongoing notifications, Health Connect, App Actions), but each of those is a project of its own.

## Building

You need JDK 17+ and the Android SDK (platform 35).

```
cd android
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # unit + fake-server protocol tests
./gradlew :app:recordPaparazziDebug # re-record screenshot goldens after UI changes
```

Install the debug APK with `adb install app/build/outputs/apk/debug/app-debug.apk`. The minimum Android version is 8.0 (API 26).

## How it talks to Hermes

| Concern | Transport |
|---|---|
| Sign-in | `POST /auth/password-login` (cookie session) |
| Socket ticket | `POST /api/auth/ws-ticket` (single use, minted per connect) |
| Profiles, chat list, transcripts | REST: `/api/profiles`, `/api/profiles/sessions`, `/api/sessions/{id}/messages` |
| Chat mutations | `PATCH`/`DELETE /api/sessions/{id}` with the identity from `/mutation-snapshot` |
| Everything live | JSON-RPC 2.0 over `/api/ws?ticket=…`: `session.create`, `session.resume`, `prompt.submit`, `session.interrupt`, `approval.respond`, `clarify.*`, `model.options`, `config.set`, plus `event` notifications and server→client requests |

Code layout under `app/src/main/java/app/hermesconduit/android/`:

| Package | Contents |
|---|---|
| `net/` | `DashboardApi` (REST), `GatewayClient` (JSON-RPC socket), URL policy, cookie jar |
| `model/` | Wire models, plus `StreamEventParser` and `MessageNormalizer`, ported from their Swift counterparts |
| `ui/` | `AppViewModel` (app state), `ChatReducer` (pure streaming reducer) and the Compose screens |
| `data/` | Encrypted credential storage |
