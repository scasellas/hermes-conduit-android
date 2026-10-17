# Conduit for Android

A native Android client for [Hermes Agent](https://github.com/NousResearch/hermes-agent), ported from the iOS app in this repository. It is written in Kotlin with Jetpack Compose and talks to the same self-hosted Hermes dashboard. There is no relay and no extra server-side setup.

> **Status:** a native port of the iOS app, built feature area by feature area against the same Hermes dashboard protocol. `docs/IOS_FEATURE_INVENTORY.md` lists every iOS feature, `docs/PARITY.md` gives the Android status of each one, and `docs/PORTING.md` describes the architecture.

## What's included

| Area | Highlights |
|---|---|
| **Sign-in and dashboards** | Password and native OAuth (PKCE, browser), Cloudflare Access service token, extra headers, multiple saved dashboards, connection test with the iOS failure explanations, biometric/screen-lock app lock |
| **Chats** | Search (titles plus Hermes full-text), filters (unread, needs approval, running, voice, scheduled, per source), live status badges, unread state, pins, archive, nesting, projects (create, folders, move), automatic titles, offline copy |
| **Conversation** | Streaming Markdown with highlighted code, tables, Mermaid and KaTeX, `MEDIA:` files and `::preview` pages, reasoning and tool cards, copy/quote/branch/react, timestamps, slash commands, compression, context breakdown, delegate agents, reasoning effort, fast mode, per-chat YOLO, model picker, steer/interrupt, attachments (camera, photos, files, shares), drafts, full-screen editor |
| **Decisions** | Approvals, clarify questions, masked sudo/secret/vault prompts, chat takeover from Desktop or the terminal, presented confirm panels (with maps navigation), Let Hermes change settings |
| **Hermes features** | Bot Mode and group chats, Kanban, Capabilities (skills, tools, MCP connectors, plugins), scheduled jobs, artifacts, workspace browser, gateway diagnostics, logs and restart, Conduit plugin install/update |
| **Settings** | Profile config pages (Chat, Workspace & safety, Memory & delegation, Model defaults), profiles manager (order, photos, background glow), appearance (language, theme, app icon), notifications, saved dashboards, connection log, about |
| **Notifications** | Background gateway connection (the push relay is APNs-only) with approval/input/response/failure/background-task notifications, Allow/Deny and Reply actions, live progress notification |
| **Platform** | `conduit://` links, share target, “Ask Hermes” text action, launcher shortcuts, Quick Settings tile, home-screen widgets, digital assistant “Ask Hermes about screen”, Android Auto and Wear OS replies through messaging notifications |
| **Languages** | The 14 iOS languages, from the iOS String Catalogs |

| **Voice** | Read Aloud, dictation (on-device, Hermes or speech server), voice conversation with barge-in and spoken controls, voice background jobs, Gemini Live / GPT-Live (WebRTC) / Grok Live with call cards, transcripts and Resume Call, wake phrase (sherpa-onnx), Calls from Hermes (full-screen ringing) |
| **On this device** | Health Connect sync, calendar & phone (events, alarms, status, Hermes-proposed changes), location sharing with places and geofences, sealed end-to-end uploads |
| **Automation** | “Send to Hermes” intent and broadcast for Tasker/MacroDroid/Automate (opt-in), `conduit://voice` |
| **Wear OS** (`:wear`) | Talk to Hermes (through the phone, direct Gemini Live, Grok/GPT-Live), recent chats, approvals, call log, tile, complication |

Release builds are split per ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`) because the wake engine and WebRTC ship native libraries.

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
