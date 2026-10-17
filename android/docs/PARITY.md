# iOS → Android parity status

This file tracks how each section of [`IOS_FEATURE_INVENTORY.md`](IOS_FEATURE_INVENTORY.md) maps to the Android app. A feature counts as **ported** when its behaviour and wire format match iOS.

**Platform gaps** are places where Android has no equivalent API, so the port uses the closest Android mechanism. They are listed on purpose and are not missing work.

Verification: JVM unit tests (protocol vectors, fake sockets, MockWebServer), Paparazzi screenshots, and Robolectric integration tests (`:app:integrationTestDebug`, `:wear:testDebugUnitTest`). The Robolectric tests drive the real activities, services, receivers, notifications and widgets against a fake Hermes server: sign-in, chat, streaming, approvals, background notifications with Reply, calls from Hermes, Send to Hermes, deep links and share, language, theme and icon switching, Glance widgets, and the watch's home and ringing screens. Hardware paths (microphone, speaker, WebRTC, Health Connect data, car head unit, Data Layer) still need a real device.

| # | Area | Status | Platform gaps / Android differences |
|---|------|--------|-------------------------------------|
| 1 | Chat transcript and rendering | Ported. Markdown with highlighting, Mermaid, KaTeX, media, HTML preview, reactions, steer, system rows, answered-clarify cards. | — |
| 2 | Composer | Ported. Attachments, slash commands, drafts, reasoning/fast/YOLO, compress, context sheet, mentions, dictation. | — |
| 3 | Session list / drawer | Ported. Search, filters, archived, unread, projects, live badges, voice-tag filing, nesting of subagents, calls and jobs, title recovery. | — |
| 4 | Settings | Ported: config engine pages, model defaults, profiles, about, app icon, agent settings sync. | — |
| 5 | Classic voice | Ported. Read Aloud, dictation, voice conversation, background jobs, spoken controls. | On-device recognition inside a conversation needs Android 13+. |
| 6 | Live voice (Gemini Live, GPT-Live, Grok Live) | Ported, including call cards, transcripts, Resume Call, call titles, setup guide, headset mute and the minimised call bar. | — |
| 7 | Wake phrase | Ported (sherpa-onnx keyword spotter with a SpeechRecognizer fallback). | Chinese phrases use the recognizer fallback. Background listening can only start while Conduit is open (Android 14). |
| 8 | Calls from Hermes | Ported. Full-screen CallStyle ringing, answer and decline, outcome outbox, settings, trace. | iOS rings through the VoIP push relay, which Android can't receive. Android rings from its own Hermes connection, and through UnifiedPush when push is set up and the plugin supports it (`docs/ANDROID_PUSH.md`); otherwise calls only come while Conduit keeps that connection open. |
| 9 | Connection, login, repair, diagnostics | Ported. | — |
| 10 | Notifier plugin management | Ported. | — |
| 11 | Push notifications / Live Activities | Notifications: local, from a remoteMessaging foreground service. Live Activities: ongoing progress notifications. | There is no APNs relay on Android. Push wake-up through UnifiedPush (Settings > Notifications) brings end-to-end sealed notifications, calls and locates while Conduit is closed, with a notifier plugin that has them: the fork [`scasellas/hermes-conduit-notifier-android`](https://github.com/scasellas/hermes-conduit-notifier-android), branch `android-unifiedpush`, implements `docs/ANDROID_PUSH.md` (install it with `hermes plugins install scasellas/hermes-conduit-notifier-android@android-unifiedpush --enable` or check out that branch). With the upstream plugin, notifications need "Stay connected" while Conduit isn't open. |
| 12 | Capabilities | Ported. | — |
| 13 | Cron | Ported. | — |
| 14 | Saved dashboards | Ported. | — |
| 15 | Health | Ported on Health Connect. | Some HealthKit metrics have no Health Connect record type. |
| 16 | Calendar & phone | Ported (CalendarContract, DND for Focus). Alarms and timers are Conduit's own, as with AlarmKit: exact `setAlarmClock` alarms (exact-alarm permission on Android 12+) that ring full screen on an Alarms channel through Silent mode and Do Not Disturb, set again after a reboot, an update or a clock or time zone change, and listed back to Hermes with their state. Reminders come from tasks apps with the OpenTasks provider (OpenTasks, Tasks.org). | Android has no system reminders: without such an app, reminders stay off. jtx Board's provider is for sync adapters only. "Phone changes" arrive on reconnect rather than by push. |
| 17 | Location and Maps | Ported (fused location, geofences, Geocoder). | iOS answers Hermes' on-demand locate through an APNs location push; Android answers locates and maps requests that come through UnifiedPush (`docs/ANDROID_PUSH.md`). Without push, Android sends a fresh fix the moment Hermes starts its location or maps tool in a chat on the socket. Maps “Go” confirmations open Maps by themselves from the chat on screen, as on iOS. No on-device routing. |
| 18 | Bot Mode | Ported. | — |
| 19 | Group chats | Ported. | — |
| 20 | Kanban | Ported. | — |
| 21 | Artifacts | Ported. | — |
| 22 | Presented screens | Ported. | — |
| 23 | Ask Hermes About Screen | Ported via the assistant (VoiceInteractionService), the share sheet and PROCESS_TEXT. | — |
| 24 | Shortcuts / App Intents | Send to Hermes: an automation intent and broadcast, opt-in. Start voice: `conduit://voice` and a launcher shortcut. Quick Settings tile. Focus filter: "Do Not Disturb & Modes" filters in Settings > Calendar & phone ("Name for Hermes", "Hold calls from Hermes"), and on Android 15 Conduit's own mode in the system's Modes. | Android has no Focus filter API, and apps can't read which system mode is on: a filter is for any Do Not Disturb or for Conduit's mode. |
| 25 | Widgets | Ported (Glance, native and character). | — |
| 26 | Apple Watch → Wear OS | Ported: bridge call, direct Gemini Live with the phone broker, Grok/GPT-Live bridge, engine picker, tile, complication, call log, approvals, Watch settings. | Calls from Hermes ring the watch too (the phone settles the ring over the Data Layer instead of the relay). watchOS-only audio workarounds and the CallKit relay handoff don't apply. |
| 27 | CarPlay → Android Auto | Templated car app (Car App Library, messaging category): chat list with replies and Listen, a voice conversation on the car's microphone with state screens, artwork and earcons, plus the settings "Choose a chat first" and "Status sounds". MessagingStyle notifications still work. | Car templates don't animate, so the orb is one still frame per state. |
| 28 | Offline chat cache | Ported. | — |
| 29 | Theming, glow, haptics | Ported. | — |
| 30 | Localization | Ported: 13 languages plus English, in-app picker. Every string the app code shows has a translation (`scripts/missing-l10n.py`). | — |
| 31 | App icons | Ported (activity-alias switching). | — |
| 32 | Misc. infrastructure | Ported: identities and aliases, approval re-hydration, clarify history, title recovery. | Transcript performance instrumentation (optional on iOS) is not ported. |
