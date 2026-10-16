# iOS features the Android port is missing

**How this was built.** I read the Android sources in `android/app/src/main/java/app/hermesconduit/android/` (about 3.7k lines). I then went through the iOS code: every Swift target, the settings screens in `Conduit/Views/AuxiliaryViews.swift`, and the RPC and endpoint calls in `HermesClient.swift`, `AppState.swift` and the AppState extensions. Endpoint strings below are copied from the code.

**What Android already does:**
- Password login and Cloudflare service token.
- Session list (`/api/profiles/sessions`), plus rename, pin, archive and delete through `PATCH`/`DELETE /api/sessions/{id}` with `mutation-snapshot`.
- Profile switch (`profiles.list`).
- Chat: `session.create`, `session.resume`, `prompt.submit`, `session.interrupt`.
- Approvals (`approval.respond`) and clarify (`clarify.lock`, `clarify.respond`).
- Model picker (`model.options`, `config.set` with key `model`) and the context % readout.
- Reconnect via `/api/auth/ws-ticket`.

**Server prefixes and conventions used below:**
- `PLUGIN` means `/api/plugins/conduit_push`, the Conduit notifier plugin on the Hermes host.
- "RPC" means JSON-RPC over the `/api/ws` gateway socket.
- Every dashboard REST call adds `?profile=<name>` for non-default profiles (`DashboardPath.withProfile`).
- `PATCH /api/sessions/{id}` also needs `profile` in the JSON body.

**Size baseline:** `Conduit/Services/AppState.swift` is 28,205 lines. Many features have their logic in a MARK section of it. I give those line ranges, e.g. "AppState §Cron L23643–24068".

---

## 1. Chat transcript and message rendering

| Feature | (a) Behaviour | (b) Files | (c) Wire | (d) Size |
|---|---|---|---|---|
| Copy response | Copy button under each assistant reply | `Views/ChatView.swift` (`AssistantMessageActions` L1632) | none | small |
| Read aloud | Speaker button under a reply speaks it, with a speed setting; Now Playing integration | `Voice/MessageReadAloudController.swift` (383), `Voice/ReadAloudNowPlaying.swift` (130), `Voice/SpokenTextFilter.swift` (185), `ChatView.swift` `ReadAloudButton` L1696 | `POST /api/audio/speak-stream` (streamed PCM; control frames `"start"`/`"end"`/`"fallback"`/`"error"`), fallback `POST /api/audio/speak` body `{"text"}` | ~700 |
| Branch from response | Fork a new session from a chosen assistant message | `ChatView.swift` L1677, `HermesClient.branchSession` L2037 | RPC `session.create` with `"parent_session_id"`, `"title"`, `"messages":[{"role","content"}]`, `"cwd"`, `"source":"desktop"`, `"cols":96` | ~150 |
| Quote reply / quote selection | Quote button attaches the whole reply as a "Replying to" chip; a text selection becomes a `>` quote in the draft | `Views/Components/ChatQuote.swift` (258), AppState §Quoting L7290 | sent as plain text (`ComposerReplyReference.outboundText`) | ~500 |
| Message reactions | Emoji chips with counts; the user can react and the agent reacts to user messages | `Components/MessageReactionViews.swift` (186), `HermesClient.reactToMessage` L2336, AppState §Reactions L17704 | RPC `message.react` `{"session_id","author","emoji"(null clears),"row_id" or "newest_role"}`; event `message.reaction` | ~500 |
| Text selection | Native selection with handles over rendered markdown | `Components/SelectableTextView.swift` (956), `MarkdownSelectionCoordinator.swift` (1117), `MarkdownSelectionHandles.swift` (548) | none | ~2.6k |
| Large-document rendering | Bounded rendering for very large messages | `MarkdownLargeDocument.swift` (957), `MarkdownLargeDocumentView.swift` (1228) | none | ~2.2k |
| Rich markdown (tables, code highlighting) | Android has basic markdown only | `MarkdownText.swift` (2799), `MarkdownRichContent.swift` (588), `StreamingText.swift` (487) | none | ~3.9k |
| Mermaid and LaTeX blocks | Diagrams and formulas drawn in a web view; tap for full screen | `Components/MarkupBlock.swift` (627) | CDN `https://cdn.jsdelivr.net/npm/mermaid@11.16.0/dist/mermaid.min.js`, `katex@0.16.22` | 627 |
| HTML preview directive | `::preview{file="…"}` renders a host-written HTML file inline and full screen | `Components/HTMLPreviewBlock.swift` (576) | `GET /api/fs/read-text?path=` or `/api/fs/read-data-url?path=` | 576 |
| `MEDIA:` tags | Agent `MEDIA:<path>` images, video, audio and docs shown inline; full-screen preview | `GatewayMediaTags.swift` (236), `GatewayMediaDataURLResolver.swift` (92), `MediaPreviewPresenter.swift` (414), `InlineAudioClip.swift` (410), `Services/TranscriptImageCache.swift` (121) | `GET /api/fs/read-data-url?path=`, `GET /api/fs/download?path=` (AppState L25911/25942); `DashboardFileDownload.swift` (174) | ~1.4k |
| User attachment previews | Image thumbnails and document chips on user bubbles | `ChatView.swift` `UserImageAttachmentPreview` L1115, `UserDocumentAttachmentChip` L1436 | none | ~400 |
| Steer bubble | Shows a delivered steer distinctly | `ChatView.swift` `SteerBubble` L953 | (Android only strips the steer text) | small |
| Review summary card | Renders the `review.summary` event, with "Open full review" | `ChatView.swift` `ReviewSummaryCard` L1866 | event `review.summary` | ~90 |
| Model change card | Inline card when the model switches | `ModelChangeSummaryCard` L1953 | event `model.update` | ~40 |
| Voice call marker card | A chat-attached voice call appears in the transcript and opens its turns | `VoiceCallMarkerCard` L1996, `Views/VoiceChatCallTranscriptSheet.swift` (51) | `PLUGIN/voice/chat-calls` | ~150 |
| Grouped activity steps | Tool and step timeline with groups, timing and a "working" line | `Views/ChatActivityRows.swift` (606), `ChatRowDisclosure.swift` (101) | none | ~700 |
| Masked input prompts | Card for `sudo` / `secret` / `vault.unlock_prompt` server requests, with Send and Skip | `ChatView.swift` `InputPromptCard` L3066, `HermesClient.respondToInputPrompt` L2367 | server-request reply `{"value": …}`. Android currently auto-answers `""`. | ~200 |
| Compaction status | "compacting" / "compacted" banners | `StreamEventParser.swift` L85 | events `compacting`, `compacted` | small |
| Session info and cwd events | Workspace and cwd shown live | `StreamEventParser.swift` | events `session.info`, `cwd.update`, `workspace.update` | small |
| Timestamps | Per-message and per-step times | `MessageTimestampFormatter` L2221 | none | ~80 |
| Scroll engine and resume position | Keeps the exact reading position across launches; "Continue" vs "Latest" | `Services/ChatScrollEngine.swift` (1090), `ChatScrollState.swift` (681), `ChatResumeCoordinator.swift` (376), `ChatResumePolicy.swift` (209), `ChatResumeStore.swift` (456), `PersistedTranscriptWindow.swift` (336) | `GET /api/sessions/{id}/messages/around?row_id=&limit=1` | ~3.1k |
| Chat text size | 5-step device-only slider for conversation text | `Components/ChatTypography.swift` (224), `AuxiliaryViews.swift` `ChatTextSizeSettings` L1386 | none | ~300 |

## 2. Chat composer

All in `Views/Components/ComposerBar.swift` (2132) unless noted.

| Feature | (a) | (b) | (c) | (d) |
|---|---|---|---|---|
| Attachments: Camera, Photo Library, Document, Workspace file | Attach menu (L984–1010); attachment strip with thumbnails; size limit | `ComposerAttachments.swift` (462), `CameraCapture.swift` (58), `ImagePicker.swift` (46), AppState §Composer L18004 and "Attachment Helper" L28107 | RPC `image.attach_bytes` `{session_id, base64, filename}`, RPC `file.attach` `{session_id, data_url, name, path}` (`HermesClient` L2619/2632) | ~1k |
| Paste image or file | Pasting a bitmap creates an attachment | `ComposerPasteTextView.swift` (799) | same as above | 799 |
| Per-chat drafts | Draft and quote kept per chat | `ComposerDraftStore.swift` (115) | none | 115 |
| Full-screen editor | Expand button opens a full-height editor | `ComposerFullEditor.swift` (96) | none | 96 |
| Return key sends | Hardware-keyboard option | `ComposerReturnKey.swift` (53) | none | 53 |
| Steer / Interrupt / Queue during a turn | Trailing control switches between send, steer, interrupt and stop | `ComposerBar` `TrailingControl` L1016 | RPC `session.steer` `{session_id,text}` (`status:"rejected"` is an error), `session.redirect`, `session.interrupt`; `config.set {"key":"busy","value":"steer"/"interrupt"/"status"}` | — |
| Reasoning effort menu | Values `none, minimal, low, medium, high, xhigh, max, ultra` | ComposerBar L1147, `ModelPickerView.swift` L41 | `config.set {"key":"reasoning","session_id","value":effort}` | — |
| Fast mode toggle | Per session | `ModelPickerView.swift` L514 | `config.set {"key":"fast","value":"fast"/"normal"}`; `session.create` `"fast":true` | — |
| Session YOLO | Per-session approval bypass, with an indicator on the model chip | `ModelPickerView` L517, `Services/SessionYoloStore.swift` (189) | `config.set {"key":"yolo","value":"1"/"0"}` | 189 |
| Model picker extras | Hide or filter providers per device; "Apply configuration" | `Views/ModelPickerView.swift` (917) | `model.options`; `config.set` value `"<model> --provider <p> --session"` + `confirm_expensive_model` | 917 |
| Slash commands | Suggestion overlay; built-in list: `new, branch, model, yolo, help, approvals, agents, background, compress, debug, goal, personality, queue, retry, rollback, save, status, steer, stop, tools, undo, usage, version` | ComposerBar `SlashSuggestionsOverlay` L2067, AppState §Slash Commands L19971–20459 | RPC `commands.catalog` `{session_id?}` (reads `categories[].pairs`, `canon`, `aliases`), `slash.exec` `{session_id, command}`, `command.dispatch` `{session_id,name,arg}` | ~600 |
| Manual compression | `/compress` with optional focus topic | AppState §Session compression L20459 | RPC `session.compress` `{session_id, focus_topic?}` (660 s timeout) | ~1.1k |
| Context sheet | Context ring opens a usage breakdown | `AuxiliaryViews.swift` `ContextSheet` L21, `ContextRingView` L2048 | RPC `session.context_breakdown` `{session_id}` | ~150 |
| Delegate agents chip and sheet | Count of active child agents; sheet lists them | `ChatSupportSheets.swift` `DelegateAgentsSheet` L346 | RPC `subagent.list` `{session_id}` | ~90 |
| Dictation | Mic button dictates into the composer; engines: Hermes, Apple on-device, speech server; custom words | `Voice/ComposerDictationService.swift` (450), `DictationEngine.swift` (114), `DictationRecognizers.swift` (502), `AppleOnDeviceSpeechTranscriber.swift` (138), `HermesLiveTranscription.swift` (160), `SpeechRecognitionLocale.swift` (77), `VoiceInputLevelMeter.swift` (71), `Views/DictationSettingsSection.swift` (118) | `POST /api/audio/transcribe` `{"data_url","mime_type":"audio/wav"}`; WS `/api/audio/transcribe-stream` (live words); `PLUGIN/speech-server/socket` | ~1.6k |
| Voice-conversation and resume-call buttons | Start voice from the composer; resume a minimised call | ComposerBar L1299/1323 | see §5/§6 | — |
| Bot @mentions | `@handle` resolution adds a teammate identification note | `Services/BotMentions.swift` (299) | appended to `prompt.submit` text | 299 |
| Location-tagged sends | A message send may trigger a location fix | `Location/LocationSharingController.swift` | see §17 | — |
| Repair / Takeover banners | "Repair Connection", "Take over this chat" | ComposerBar L789/850; see §9 | — | — |
| `prompt.submit` extras | `"surface"`, `"voice_turn":true` | `HermesClient.sendPrompt` L2161 | — | — |

## 3. Session list, sidebar and V2 Chats tab

| Feature | (a) | (b) | (c) | (d) |
|---|---|---|---|---|
| V2 shell | Tab bar Chats / Tasks / Bots / Settings, profile strip top bar, project pages, artifacts route. Default ON (`"conduit.useV2Shell"` = true). | `Views/V2/V2Shell.swift` (607), `V2TopBar.swift` (337), `V2ChatsView.swift` (1409), `V2SessionRow.swift` (319), `V2ConversationScreen.swift` (327), `V2ProjectPage.swift` (275), `V2SettingsTab.swift` (89), `Services/AppState+V2Shell.swift` (64) | — | ~3.4k |
| Legacy sidebar | Sessions / Bots / Cron / Kanban tabs; persistent sidebar on iPad | `Views/SidebarView.swift` (2426), `SidebarTab.swift`, `SidebarLayout.swift` (61), `RootView.swift` (697) | — | ~3.2k |
| Search sessions | `.searchable` over sessions and projects; server search for older chats | SidebarView L424, `Voice/VoiceChatFinder.swift` (238) | `/api/sessions/search` | ~300 |
| Filters | `all, unread, needsApproval, running, voice, scheduled, source.<x>`; sources `chat, voice, voice_job, discord, telegram, api, webhook, cron, other`; reorderable | `V2ChatsView.swift` `V2SessionFilter` L17, SidebarView `SessionFilterOrderSheet` L1046, `Models/Models.swift` `SessionSource` | `/api/profiles/sessions?...&exclude_sources=cron` vs `&source=cron` | ~400 |
| Archived conversations sheet | List, search, restore, delete | SidebarView `ArchivedSessionsSheet` L1078 | `/api/profiles/sessions?...&archived=only&order=recent&exclude_sources=cron`; `PATCH /api/sessions/{id}` | ~110 |
| Unread / read state | Unread dots, "Mark all as read", per-row toggle; mirrors which chats Desktop has open | `Services/ChatReadState.swift` (321), SidebarView `ReadStateToggleButton` L1379, AppState §Read state L6276–6670 | session rows `"unread"`, `"last_read_at"`; `PATCH /api/sessions/{id}` `{"unread":bool}`; `PLUGIN/sessions/desktop-views` | ~700 |
| Server-synced pins | Pins via Hermes `pinned`, with a local fallback | `Services/SessionPinSync.swift` (280) | `PATCH /api/sessions/{id}` `{"pinned"}`. Android has the basic version. | 280 |
| Live status badges | Running, needs-approval and voice indicators per row | `Services/SessionLiveStatusIndex.swift` (49), `SessionPresentationCache.swift` (1699) | RPC `session.active_list` `{profile?}` → `sessions[]`; RPC `approval.pending` | ~1.7k |
| Session nesting | Child sessions (delegations, branches) nested under parents; "From <parent>" breadcrumb | `Models/SessionNesting.swift` (329) | `parent_session_id` in rows | 329 |
| Projects | Create (name + folders), rename, delete, list sessions, "Move to Project", new chat in project, folder picker | SidebarView `ProjectRow`, `ProjectSessionsSheet`, `ProjectCreateSheet` L1718, `ProjectFolderPickerSheet`, `MoveToProjectMenu` L1481; `V2ProjectPage.swift` | RPC `projects.tree {"preview_limit":3}`, `projects.project_sessions {"project_id"}`, `projects.create {name, folders}`, `projects.update {id,name}`, `projects.delete {id}`, `session.workspace.move {session key, cwd}`; `/api/fs/list?path=`, `POST /api/fs/write-text` (project idea) | ~1k |
| Auto title generation | Generates a title after the first turn | AppState §Titles L5331, `HermesClient.generateSessionTitle` L2119 | RPC `llm.oneshot`; `session.title`; config keys `auxiliary.title_generation.enabled` / `.language` | ~300 |
| Conversation "…" menu | Refresh, Artifacts, Rename / Pin / Archive / Delete, Gateway status | `V2ConversationScreen.swift` L243 | — | — |
| Multiple session pages | `limit=200&offset=…&min_messages=1&order=recent` | `HermesClient` | — | — |

## 4. Settings

The structure is in `Views/AuxiliaryViews.swift` (3327). Home page groups, V2 layout L1024:
- **Profile:** Connection, Approvals, Edit profile.
- **Voice:** Voice, Calls from Hermes.
- **Hermes:** Model, Chat, Memory & delegation, Capabilities.
- **App:** Profiles, Appearance, Notifications, Ask Hermes About Screen.
- **On this device:** Health, Location, Calendar & phone.
- **Server:** Saved Dashboards, Conduit plugin, Connection Setup.
- **About:** About & privacy.

There is also a "Update the Conduit plugin" notice banner.

Most profile settings use one generic mechanism, `ProfileConfigSettingsPage` (L2036, about 170 lines): `GET /api/config` (+profile), edit the key path, then `PUT /api/config` with `{"config":…, "profile":…}`. The exception is `memory.provider` / `context.engine` on the default profile, which use `PUT /api/dashboard/plugin-providers` `{"memory_provider"}` / `{"context_engine"}` (AppState L23927).

### 4.1 Profile (`ProfileSettingsDetail` L1270)
- Shows the active profile.
- "Display name" field for the default profile; device-local, stored in `ProfileAppearanceStore`.

### 4.2 Model (`ProfileModelSettingsDetail` L1791)
- Default provider, model and reasoning (`none…ultra`).
- Loads `GET /api/model/options?explicit_only=true` and `/api/config`.
- Saves through `POST /api/model/set`, then patches `/api/config` (AppState L23894–23909).
- Also reads `/api/model/info`.

### 4.3 Chat (`ChatSettingsDetail` L1320)
Device-local:
- **When returning to Conduit:** "Open to" (sessions / conversation; legacy UI only) and "Conversation" (Continue / Latest).
- **During a response:** Steer / Interrupt, via `config.set busy`.
- **Chat text size:** slider.
- **Keyboard:** "Return key sends".
- **Attachments:** "Maximum file size" menu (16 MB default, up to 256 MB, with a risk warning).
- **Chats open on your computer:** "Take over automatically".
- **Haptic feedback:** toggle.

Hermes config keys:
- `display.personality`: options `"", helpful, concise, technical, creative, teacher, kawaii, catgirl, pirate, shakespeare, surfer, noir, uwu, philosopher, hype` plus server personalities.
- `timezone`: text.
- `display.show_reasoning`: toggle.
- `display.tool_progress`: `all` / `off`.
- `display.expand_tools`: toggle.
- `display.memory_notifications`: `default` / `on` / `off`.
- `agent.image_input_mode`: `auto` / `native` / `text`.

### 4.4 Workspace & safety (`workspaceFields` L824)
- `terminal.cwd`: text.
- `code_execution.mode`: `project` / `strict`.
- `approvals.mode`: `manual` / `smart` / `off`.
- `security.redact_secrets`: toggle.
- `security.allow_private_urls`: toggle.

### 4.5 Memory & delegation (`MemorySettingsDetail` L1989, `memoryFields` L833)
- `memory.provider`: options.
- `memory.memory_enabled`, `memory.user_profile_enabled`: toggles.
- `context.engine`: options.
- `compression.enabled`: toggle.
- `compression.threshold` (0.8), `compression.target_ratio` (0.5), `compression.protect_last_n` (8): numbers.
- `delegation.max_concurrent_children` (2): number.
- **Delegate model** section (`DelegationModelSettings` L1892): `delegation.provider`, `delegation.model`, `delegation.reasoning_effort`, each with an "Inherit" option.

### 4.6 Voice
See §5. `VoiceSettingsView.swift` (1764) plus the provider views.

### 4.7 Calls from Hermes
See §8. `HermesCallSettingsViews.swift` (430).

### 4.8 Capabilities
See §12.

### 4.9 Gateway (`GatewaySettingsDetail` L2205)
- Server and status rows; Reconnect; "Restart Gateway" with confirmation and status view; "Diagnostics & logs".
- **Cloudflare Access:** "Use service token", Client ID / Client Secret.
- **Extra headers:** editor for proxy headers (`CustomHeaders.swift` 287, `CustomHeadersEditor.swift` 148), sent to this origin only over HTTPS.
- "Sign Out of This Dashboard".

### 4.10 Conduit plugin
See §10. `ConduitPluginViews.swift` (529).

### 4.11 Saved Dashboards
See §14.

### 4.12 Appearance (`AppearanceSettingsDetail` L2302)
- Theme: Dark / Light / System.
- App language: System plus 13 languages.
- App icon: `dark` / `light` (`Models/AppIconChoice.swift`).
- iPad "Persistent session sidebar".

### 4.13 Notifications (`NotificationsSettingsDetail` L2413)
- **This iPhone:** status; Enable / Turn off.
- **Lock Screen:** "Show progress on the Lock Screen" (Live Activities).
- **Notify me when:** Approval needed, Input needed, Response ready, Turn failed, Background task finished, Approval & input sound, Completion sound, Show previews, Approval cards in pushes.
- **Replies to messages from:** per-source toggles (`ReplySourcesSettingsSection.swift` 140).
- **Compatibility:** relay version, pairings per dashboard.
- **Connect a Hermes profile:** one-tap pairing, or a pairing code plus the command `hermes conduit-push pair <code>`.
- **Verify pairing:** `hermes conduit-push status`, `hermes conduit-push test`.
- **Push relay:** URL field (default `https://push.milim.dev`), Save / Retry / Reset.

### 4.14 Health, Location, Calendar & phone
See §15–17.

### 4.15 Ask Hermes About Screen
See §20. `ScreenQuestionViews.swift` (208).

### 4.16 About & privacy (L3022)
- Version; data handling text.
- Links: privacy `https://kaishi00.github.io/hermes-conduit-notifier/privacy/`, support `…/support/`, GitHub.
- Disclaimer.

### 4.17 Profiles manager
`ProfilePickerSheet.swift` (338):
- Reorder profiles.
- Per-profile photo: choose or remove.
- "Background glow" colour, or Automatic.
- Unread dots per profile.

Wire: RPC `profiles.get_asset` / `profiles.set_asset` `{"name","asset":"avatar","data":<dataURL> | "clear":true}` (unscoped); `ProfileAppearanceStore.swift` (423), `ProfileGlowColors.swift` (216).

### 4.18 Let Hermes change settings
"Let Hermes change settings": Off / Ask me every time / Small changes without asking.
- Hermes proposes Conduit setting changes; small ones apply with Undo, others ask.
- Files: `Services/AgentSettings.swift` (453), `AppState+AgentSettings.swift` (286), `Views/AgentSettingsViews.swift` (243).
- Wire: `PUT PLUGIN/conduit-settings` (both directions in one call); capability `"conduit-settings"`.
- Exposed keys include `voice.mode`, `voice.continuous_conversation`, `voice.keep_listening_when_locked`, `voice.gemini.voice`, `voice.gemini.search`, `voice.gpt.voice`, `voice.live.answer_length`, `voice.live.tone`, `voice.live.backchannels`, `voice.live.ask_before_sending`, `voice.live.save_calls`, `voice.live.speaker_talk_over`, `stt.provider`, `stt.enabled`, `stt.streaming`, `tts.provider`, `tts.elevenlabs.voice_id` / `model_id` / `base_url`, `voice.speech_server.url` / `api_key`.

## 5. Voice: classic push-to-talk and voice conversation

| Feature | (a) | (b) | (c) | (d) |
|---|---|---|---|---|
| Voice conversation | Full-duplex loop: speech detection, transcription, `prompt.submit` with `voice_turn`, streamed TTS; barge-in, echo cancellation, continuous mode, keep listening when locked, keep screen awake | `Voice/VoiceConversationController.swift` (2009), `AVAudioCaptureService.swift` (730), `AVSpeechPlaybackService.swift` (516), `EchoCancellingVoiceConversationAudio.swift` (472), `VoiceSpeechDetector.swift` (219), `VoiceBargeInRoutePolicy.swift` (88), `VoiceAudioSessionCoordinator.swift` (456), `VoiceAudioSessionConfiguration.swift` (80), `VoiceAudioEngineRecovery.swift` (110), `HeadsetMicrophoneMute.swift` (144), `VoiceTypes.swift` (782), `Views/VoiceConversationSheet.swift` (663), `LiquidOrb/LiquidOrbView.swift` (398) | `POST /api/audio/transcribe`, WS `/api/audio/transcribe-stream`, `POST /api/audio/speak-stream`, `/api/audio/speak`; `prompt.submit` `"voice_turn":true` | ~7k |
| Spoken controls | Stop and end-conversation phrases; whole-utterance match; editable lists | `Shared/WatchVoice/VoiceSpokenCommands.swift` (184), VoiceSettingsView "Spoken Controls" L1162 | local | ~300 |
| Voice background jobs | Spoken requests start separate Hermes sessions as "voice jobs"; results read back; follow-ups | `Voice/VoiceBackgroundJobs.swift` (3227), `VoiceReadBack.swift` (152), `Views/LiveVoiceCallJobs.swift` (396), `Shared/WatchVoice/VoiceJobProfiles.swift` (145) | `session.create` / `prompt.submit` per job; `session.active_list {profile}` | ~3.9k |
| Voice job and reply model | Separate model for voice jobs and voice replies | `GeminiLiveViews.swift` "Voice jobs" L385, "Voice replies" L536 | `GET /api/model/auxiliary`, `POST /api/model/set` (AppState L1818/1828) | — |
| Voice history | Saved calls become sessions under Voice / Voice Jobs; unsaved-call queue with retry | `Voice/VoiceHistory.swift` (872), AppState §Voice history L4552, §Outbox L5169 | `PLUGIN/voice/sessions`, `PLUGIN/voice/tags`, `PLUGIN/voice/summary`, `PLUGIN/voice/chat-calls` | ~1.5k |
| Minimised call bar | Call continues while browsing; resume from composer | `Views/MinimisedLiveVoiceBar.swift` (308) | — | 308 |
| Voice-to-chat attach | Live call attached to a chat | AppState §Live voice attached to a chat L5791 | `PLUGIN/voice/chat-calls` | ~500 |
| Find chats by voice | "find / open / read chat" tools | `VoiceChatFinder.swift` (238) | `/api/sessions/search` | 238 |
| Voice settings page | See the list after this table | `Views/VoiceSettingsView.swift` (1764), `SpeechServerSettingsView.swift` (210), `VoiceAPIKeyRow.swift` (164), `LiveVoiceSetupSheet.swift` (154), `Voice/HermesVoiceConfigurationService.swift` (951), `VoiceSetupDefaults.swift`, `VoiceSetupIssue.swift` | `GET /api/config/schema`, `GET/PUT /api/env` `{key,value}`, `PUT /api/tools/toolsets/{stt\|tts}/provider`, `/api/tools/toolsets/stt/config`, `/api/tools/toolsets/tts/config`, `PLUGIN/speech-server/status`, `PLUGIN/speech-server/test` | ~3.2k |

Voice settings page sections:
- "Set up voice" / "Enable voice on this device".
- "Voice mode" picker: classic / gemini / gpt / grok.
- "Speech to text" and "Assistant speech" provider sections, with "Show words while you speak".
- "Credentials on Hermes" (API keys).
- "Conversation": Continuous Conversation, Keep listening when locked, Keep phone awake.
- "Speaker and call screen": Talk over Hermes on the speaker, Animate the call orb.
- "Read Aloud" speed.
- "Spoken Controls".
- "Wake phrase" (§7).
- "Unsaved voice calls".
- Speech-server (self-hosted STT) config: `voice.speech_server.url`, `voice.speech_server.api_key`.

## 6. Live voice providers

| Provider | (a) | (b) | (c) | (d) |
|---|---|---|---|---|
| Gemini Live | Realtime voice to Google using an ephemeral token minted by the plugin. Function tools: `start_job, list_jobs, cancel_job, interrupt_job, send_request, set_ask_first, web_search, recall_memory, end_conversation, ask_thread, read_last_reply, show_on_screen, call_me_when_done, answer_approval, answer_question, find_chat, open_chat, read_chat`, plus host tools. Settings: enable, search mode, voice, Use Hermes memory, Use Hermes personality, Talk over it on the speaker, Save calls to Sessions. | `Voice/GeminiLive/GeminiLiveConversationController.swift` (1553), `GeminiLiveTokenClient.swift` (288), `GeminiLiveToolBridge.swift` (1184), `LiveVoiceEchoCancellingAudio.swift` (671); `Shared/GeminiLive/GeminiLiveProtocol.swift` (317), `GeminiLiveSession.swift` (665), `GeminiLiveToken.swift` (88); `Views/GeminiLiveViews.swift` (600) | `PLUGIN/gemini-live/status`, `PLUGIN/gemini-live/token`, `PLUGIN/memory/context`, `PLUGIN/memory/recall`, `PLUGIN/personality`, `PLUGIN/web-search`, `PLUGIN/web-search/status` | ~5.4k |
| GPT-Live | WebRTC to OpenAI Realtime (session minted by the plugin); delegation bridge to Hermes jobs. Billing modes: subscription or API key; API voice; max minutes; "Calls from Hermes on the API key". | `Voice/GPTLive/GPTLiveAudioLink.swift` (228), `GPTLiveClient.swift` (261), `GPTLiveConversationController.swift` (1044), `GPTLiveDelegationBridge.swift` (1476), `GPTLivePeer.swift` (267, `RTCPeerConnection`), `GPTLiveProtocol.swift` (278), `GPTLiveSession.swift` (343); `Views/GPTLiveViews.swift` (401) | `PLUGIN/gpt-live/status`, `PLUGIN/gpt-live/session`. Realtime events: `session.started`, `session.updated`, `session.closed`, `session.input_transcript.delta`, `session.output_transcript.delta`, `session.commentary.append`, `session.thinking.append`, `session.context.append`, `session.delegation.created`, `delegation.created`, `delegation.context.append`, `turn.done`, `response.*`, `input_audio_buffer.*`, `conversation.item.create`. Needs a WebRTC dependency on Android. | ~4.3k |
| Grok Live | WebSocket relayed through the plugin. Settings mirror Gemini (memory, personality, talk over, save calls). | `Voice/GrokLive/GrokLiveClient.swift` (90); `Shared/GrokLive/GrokLiveAvailability.swift` (48), `GrokLiveProtocol.swift` (298), `GrokLiveSession.swift` (532); `Views/GrokLiveViews.swift` (168) | `PLUGIN/grok-live/status`, `PLUGIN/grok-live/socket` | ~1.1k |
| Live call UI and style | Call sheet, jobs list, setup sheet. "Live call style": greet on connect, answer length, tone, Backchannels, "Ask before sending to Hermes", with an instructions preview. | `Views/LiveVoiceCallSheet.swift` (992), `LiveVoiceStyleViews.swift` (281), `Voice/LiveVoiceStyle.swift` (135) | keys `voice.live.*` | ~1.4k |
| Host tools in calls | `conduit_health`, `conduit_location`, maps and phone tools callable by the live model | `Voice/VoiceHostTools.swift` (252), `Services/AppState+VoiceHostTools.swift` (321), `Location/MapsVoiceTool.swift` (268), `Phone/PhoneVoiceTools.swift` (360) | `PLUGIN/voice-tools` | ~1.2k |

## 7. Wake phrase

- **Behaviour:** always-on keyword spotting ("Hey Hermes"-style). Per-profile phrases; "Start a new conversation", "Listen on CarPlay", "Listen while other audio plays"; pauses for other audio.
- **Files:** `Voice/Wake/AppleSpeechWakeWordService.swift` (444), `SherpaWakeWordService.swift` (98; sherpa-onnx KWS), `WakePhraseCompiler.swift` (163; tokens.txt), `WakePhraseMatcher.swift` (80), `WakeConfigurationStore.swift` (99), `WakeLifecycleCoordinator.swift` (106), `WakeModelDescriptor.swift` (75), `PendingVoiceIntentStore.swift` (175), `PendingVoiceLaunchPolicy.swift` (248); `Services/AppState+WakePhrase.swift` (280); VoiceSettingsView L1419.
- **Wire:** local only.
- **Size:** ~1.8k.

## 8. Calls from Hermes (Hermes phones you)

- **Behaviour:**
  - "Call me when it's done" watches jobs.
  - The plugin sends a call request; it rings via VoIP push and CallKit, or arrives as a notification with a Talk button.
  - Missed-call outcomes are reported.
  - Presence/hold while a call runs.
  - Test call.
  - Settings: "Hermes can call me", "Ring like a phone call", "Call when I ask", "Hermes decides when to call", "Call about approvals and questions", rate limits (min gap / per hour / per day), free-text rules, avoid places, "Don't call me when Conduit can't say where I am".
  - Focus filter "Hold calls from Hermes".
- **Files:** `Voice/HermesCalls.swift` (1095), `HermesNativeCalls.swift` (960), `HermesCallTrace.swift` (41); `Services/AppState+HermesCalls.swift` (590); `Views/HermesCallSettingsViews.swift` (430); `Intents/HermesFocusFilter.swift` (58).
- **Wire:**
  - `GET/PUT PLUGIN/calls` `{"settings":{"enabled","when_asked","min_gap_s","per_hour","per_day","decides","alerts","rules","avoid_places","avoid_unknown_place"}}`.
  - `PLUGIN/calls/watches` `{"session_ids","title","hold_s"}`, with states `watching`, `ended`, `gone`.
  - `PLUGIN/calls/presence` `{"hold_s"}`.
  - `PLUGIN/calls/outcomes` `{"call_id","session_ids","outcome","age_s"}`.
  - Push type `call.requested`; notification category action `"HERMES_CALL_TALK"`.
- **Size:** ~3.1k. On Android this needs FCM high-priority pushes and ConnectionService / full-screen-intent notifications.

## 9. Connection, login, repair and diagnostics

| Feature | (a) | (b) | (c) | (d) |
|---|---|---|---|---|
| Native OAuth (PKCE) sign-in | Browser sign-in with a loopback callback; provider choice; token refresh | `Services/NativeOAuth.swift` (964), `NativeAuthClient.swift` (727) | `GET /api/auth/providers`; `{base}/auth/native/authorize?code_challenge=…&code_challenge_method=S256&redirect_uri=http://127.0.0.1:<port>/callback&state=…&provider=…`; `POST /auth/native/token`, `POST /auth/native/refresh` | ~1.7k |
| Browser/WebView fallback | In-app web sign-in for dashboards without native auth | `Views/LoginView.swift` `AuthWebView` L219, `AuthWebViewNavigationPolicy` | cookies | ~440 |
| New login flow | Address step, credentials page, dashboards list screen, failure cards with troubleshoot | `Views/Login/LoginFlowModel.swift` (638), `LoginScreens.swift` (1190) | `/api/status`, `/api/auth/providers`, `/auth/password-login` | ~1.8k |
| Connection Setup wizard | Step-by-step scheme / host / port / credentials, staged probe tests with failure explanations, apply plan | `Views/ConnectionSetupView.swift` (914), `ConnectionSetupForm.swift` (491); `Services/ConnectionSetupFlow.swift` (612), `ConnectionSetupTest.swift` (587), `ConnectionSetupDraft.swift` (220), `ConnectionSetupApplication.swift` (175) | `/api/status`, `/api/auth/providers`, `/api/auth/ws-ticket`, `/api/ws` probes | ~3k |
| Connection repair | Classifies failures; "Repair Connection" sheet | `Services/ConnectionRepair.swift` (158), `ConnectionFailure.swift` (334), AppState §Connection Repair L9528 | — | ~900 |
| Connection timeline | Copyable log of connection events | `Services/ConnectionTimeline.swift` (125), `ChatSupportSheets.swift` L136 | — | ~200 |
| Gateway diagnostics sheet | Gateway state, version, pid, messaging connectors, last 80 log lines, retry | `Views/ChatSupportSheets.swift` `GatewayDiagnosticsSheet` L12 | `GET /api/status`, `GET /api/messaging/platforms` (`platforms[]`), `GET /api/logs?lines=80&component=gateway` | ~190 |
| Restart Gateway | Confirm, restart, then watch it come back | `Services/GatewayRestart.swift` (343), `AppState+GatewayRestart.swift` (74), `Views/GatewayRestartViews.swift` (165) | `POST /api/gateway/restart`; poll `/api/actions/gateway-restart/status?lines=20` and `/api/status` (states `starting`, `stopped`, `startup_failed`, `draining`) | ~580 |
| Chat takeover | Send refused with `SESSION_NOT_OWNED` (open in Desktop or TUI) offers "Take over this chat", or does it automatically | `Services/ChatTakeover.swift` (186), AppState §Chat takeover L18720 | `POST PLUGIN/sessions/takeover` `{"session_ids":[…]}` → `taken_over` / `free` / `busy` / `same_host` | ~400 |
| Custom headers | Per-origin extra headers | `Services/CustomHeaders.swift` (287), `Views/CustomHeadersEditor.swift` (148) | applied to all requests | ~435 |
| Workspace browser | Browse host cwd, preview text, "Save to Files" | `ChatSupportSheets.swift` `WorkspaceBrowserSheet` L201, `WorkspaceFilePreviewSheet` L281 | `/api/fs/list?path=`, `/api/fs/read-text?path=`, `/api/fs/read-data-url?path=` | ~230 |
| Background requests and host pass | Dashboard calls while the app is in the background | `Services/DashboardBackgroundRequests.swift` (308), `DashboardTicketBridge.swift` (1162) | `/api/auth/ws-ticket` | ~1.5k |
| Foreground refresh and authoritative reconciliation | Refresh on foreground; turn-state correction; cross-surface freshness | AppState §L10695–15300 | `session.active_list`, `approval.pending`, `session.resume` | ~4.6k |

## 10. Conduit notifier plugin management

- **Behaviour:**
  - Detects the plugin version and capabilities and nudges an update.
  - One-tap install / update and "restart Hermes".
- **Files:** `Services/NotifierPluginStatus.swift` (189), `NotifierPluginUpdate.swift` (335), `AppState+NotifierPluginUpdate.swift` (232); `Views/ConduitPluginViews.swift` (529).
- **Wire:**
  - `GET PLUGIN/capabilities`.
  - `GET /api/dashboard/agent-plugins`, `POST /api/dashboard/agent-plugins/conduit_push/update`, `POST /api/dashboard/agent-plugins/install`.
  - `POST PLUGIN/self/restart`.
  - Capability strings used: `"pairing"`, `"conduit-settings"`, `"artifacts"`, `"cron-notifications"`, `"reply-sources"`.
- **Size:** ~1.3k. This is a prerequisite for most plugin-based features.

## 11. Push notifications and Live Activities

| Feature | (a) | (b) | (c) | (d) |
|---|---|---|---|---|
| Relay registration and pairing | Register with the relay; per-dashboard pairing (one-tap or code); preferences; relay move | `Services/PushNotificationService.swift` (2043) | Relay (default `https://push.milim.dev`): `/v1/meta`, `/v1/installations`, `/v1/installations/{id}`, `/v1/installations/{id}/pairings`. Dashboard: `PLUGIN/pair`, `PLUGIN/e2e` (E2E key provisioning). APNs-only today; Android needs FCM support on the relay. | ~2k |
| E2E-encrypted payloads | 32-byte root secret per pairing, HKDF-SHA256, ChaCha20-Poly1305; service extension decrypts | `Shared/NotificationE2E.swift` (570), `ConduitNotificationService/NotificationService.swift` (77) | types `approval.needed`, `input.needed`, `turn.failed`, `background_task.finished`, `call.requested`, response ready | ~650 |
| Decision cards from notifications | Approve, or answer clarify, from a push | PushNotificationService `respondToRelayDecision` L988 | `/v1/decisions/{requestId}/respond` (sealed body) | — |
| Tap routing | Open the right dashboard, profile and session | `PushNotificationService.route` L320, `parseNotificationTarget` | payload `stored_session_id` / `session_key`, `dashboard_id`, `gateway_id` | — |
| Reply-source filters | Which sources' replies notify | `Services/ReplySourceNotifications.swift` (62), `Views/ReplySourcesSettingsSection.swift` (140) | `PLUGIN/notifications/reply-sources` `{"source","notify"}` | ~200 |
| Cron job notify toggle | Per-job mute | `Services/CronNotifications.swift` (43), `AppState+CronNotifications.swift` (114) | `PLUGIN/cron/notifications` `{"job_id","notify"}` | ~160 |
| Live Activity / Dynamic Island | Running turn on the Lock Screen: what it's doing, time, tools, context; pushed via the relay | `Services/LiveTurnActivities.swift` (336), `ConduitWidgets/HermesTurnLiveActivity.swift` (322), `Shared/Widgets/HermesTurnActivity.swift` (171) | `/v1/installations/{id}/live-activities` `{token, session_ids}`. Android analog: an ongoing notification. | ~830 |

## 12. Capabilities (Skills, Tools, Connectors, Plugins)

Files: `Views/CapabilitiesView.swift` (206), `Views/Capabilities/CapabilityComponents.swift` (347), `Services/CapabilitiesStore.swift` (578), `Models/CapabilityCatalogModels.swift` (620). Total about 4.3k lines.

- **Skills tab** (`CapabilitySkillsTab.swift` 439)
  - Behaviour: list and toggle skills, view skill content, browse the official hub, preview and install.
  - Wire: `GET /api/skills`, `PUT /api/skills/toggle` `{name, enabled, profile}`, `GET /api/skills/content?name=`, `GET /api/skills/hub/official`, `GET /api/skills/hub/preview?identifier=`, `POST /api/skills/hub/install`; poll `GET /api/actions/{name}/status?lines=200`.
- **Tools tab** (`CapabilityToolsTab.swift` 558)
  - Behaviour: enable or disable toolsets, pick a provider per toolset, terminal backend, API keys, usage counts.
  - Wire: `GET /api/tools/toolsets`, `PUT /api/tools/toolsets/{name}` `{enabled, profile}`, `GET /api/tools/toolsets/{name}/config`, `PUT /api/tools/toolsets/{name}/provider`, `GET /api/tools/terminal/backends`, `PUT /api/tools/terminal/backend`, `PUT /api/env` `{key, value}`, `GET /api/analytics/usage?days=365`.
- **Connectors (MCP) tab** (`CapabilityConnectorsTab.swift` 662)
  - Behaviour: list servers, probe or test, enable or disable, remove, install from catalog with env vars, OAuth sign-in flow.
  - Wire: `GET /api/mcp/servers`, `GET /api/mcp/catalog`, `POST /api/mcp/catalog/install` `{name, env, enable:true}`, `POST /api/mcp/servers/{n}/test`, `PUT /api/mcp/servers/{n}/enabled`, `DELETE /api/mcp/servers/{n}`, `POST /api/mcp/servers/{n}/auth` → `{flow_id, url}`, `GET` / `DELETE /api/mcp/oauth/flows/{id}`.
- **Plugins tab** (`CapabilityPluginsTab.swift` 479)
  - Behaviour: list agent plugins, enable or disable, update (with capability acceptance), remove, install from catalog or hub.
  - Wire: `GET /api/dashboard/agent-plugins`, `POST /api/dashboard/agent-plugins/{name}/enable|disable`, `POST …/{folder}/update`, `DELETE …/{folder}`, `POST /api/dashboard/agent-plugins/install`, `GET /api/dashboard/plugins/catalog`, `GET /api/dashboard/plugins/hub`.

## 13. Cron / scheduled jobs

- **Behaviour:**
  - List and search jobs (schedule, next run, enabled switch).
  - Detail sheet with the last 20 runs; pause / resume / run now; per-job notification mute.
  - Cron-sourced sessions show under a "scheduled" filter.
  - In V2 this is the "Scheduled" half of the Tasks tab.
- **Files:** SidebarView `CronList` L1906, `CronJobRow` L2146, `CronJobDetailSheet` L2178; `Views/V2/V2TasksView.swift` (373); AppState §Scheduled jobs L23643–24068; `Models/Models.swift` `CronJob` / `CronRun` / `CronJobAction` (`pause`, `resume`, `trigger`).
- **Wire:**
  - `GET /api/cron/jobs`, `GET /api/cron/jobs/{id}/runs?limit=20`, `POST /api/cron/jobs/{id}/{pause|resume|trigger}` body `{"profile"}`.
  - RPC fallback `cron.list`, `cron.runs`.
  - `/api/profiles/sessions?...&source=cron`.
- **Size:** ~1.2k.

## 14. Saved dashboards (multi-server)

- **Behaviour:**
  - Several Hermes dashboards; switch, add, remove, sign out per dashboard.
  - Saved sign-in kinds: `biometric` (Face ID unlocks saved credentials), `password`, `session`, `signInNeeded`.
  - Everything (profiles, photos, pins, pairings) is scoped per dashboard.
- **Files:** `Services/SavedDashboardRegistry.swift` (411), `Views/SavedDashboardsView.swift` (223), `Login/LoginScreens.swift` `LoginDashboardsScreen` L501 and `AddDashboardSheet` L1148, `Services/BiometricAuth.swift` (56), AppState §Saved dashboards L9935–10695, Keychain §L27908–28107.
- **Wire:** none beyond login.
- **Size:** ~2.2k.
- **Note:** biometrics in iOS are used only for saved-dashboard sign-in, not as a general app lock.

## 15. Health (Apple Health sync; Android counterpart is Health Connect)

- **Behaviour:**
  - Opt-in categories: `activity`, `heart`, `sleep`, `workouts`, `body`, `vitals`, `fitness`, each with "Include detailed readings".
  - History depth: `days30` / `days90` / `year` / `all`.
  - Background sync; wipe the host copy.
  - Live voice calls may read it (per-mode consent).
  - Encryption: X25519 key agreement, ChaCha20-Poly1305 batches.
- **Files:** `Health/HealthKitSource.swift` (422), `HealthSyncBatch.swift` (276), `HealthSyncCrypto.swift` (139), `HealthSyncEngine.swift` (511), `HealthSyncModels.swift` (379); `Services/AppState+HealthSync.swift` (271); `Views/HealthSettingsViews.swift` (431).
- **Wire:** `PLUGIN/health/key`, `/health/ingest`, `/health/status?device_id=`, `/health/wipe` `{device_id}`.
- **Size:** ~2.4k.

## 16. Calendar & phone (EventKit, Focus, alarms, contacts)

- **Behaviour:**
  - Share calendars (pick which) and reminders, with "Include notes and attendees" and "Include attendees' email addresses".
  - "Changes from Hermes": approve or auto-add events and reminders Hermes requests.
  - Focus and phone status (battery etc.); Alarms and timers (AlarmKit); People (contacts).
  - Live-call voice-read consent.
- **Files:** `Phone/EventKitPhoneSource.swift` (496), `PhoneAlarmSource.swift` (276), `PhonePeople.swift` (212), `PhoneStatusSource.swift` (138), `PhoneSyncCrypto.swift` (124), `PhoneSyncEngine.swift` (821), `PhoneSyncModels.swift` (450), `PhoneVoiceTools.swift` (360); `Services/AppState+PhoneSync.swift` (308); `Views/PhoneSettingsViews.swift` (814).
- **Wire:** `PLUGIN/phone/key`, `/phone/ingest` (sections `{"full","upsert","delete"}`, `status`), `/phone/exchange` `{"call_id","results"}`, `/phone/status?device_id=`, `/phone/wipe`.
- **Size:** ~4k.

## 17. Location and Maps

- **Behaviour:**
  - Layers: "Share while I'm talking to Hermes" and "Share in the background".
  - Precision: `places` / `area` / `precise`.
  - Saved places with arrive/leave events and a "Private" flag; Pause; history on the host (wipe).
  - "When Hermes read your location" log; place rules (delete).
  - "Keep maps lookups on this iPhone": MapKit answers `conduit_maps` search, directions and ETA.
  - On-demand locate pushed by the agent, answered by an extension.
- **Files:** `Location/LocationBackgroundMonitor.swift` (207), `LocationFixProvider.swift` (212), `LocationSharingController.swift` (648), `LocationSyncEngine.swift` (466), `LocationVoiceCrypto.swift` (46), `MapsNavigator.swift` (127), `MapsVoiceTool.swift` (268); `Shared/Location/*` (LocationBatch 207, LocationCrypto 116, LocationHostClient 136, LocationLocate 131, LocationMapsCrypto 70, LocationModels 420, LocationSharedStore 232, MapsEngine 954); `ConduitLocationPush/LocationPushService.swift` (339); `Services/AppState+Location.swift` (175); `Views/LocationSettingsViews.swift` (1004).
- **Wire:**
  - `PLUGIN/location/key`, `/location/ingest`, `/location/reads?limit=50`, `/location/rules`, `DELETE /location/rules/{id}`, `/location/wipe` `{device_id}` or `{all:true}`, `/location/status`.
  - Push stub `conduit {type:"location.requested", e2e:1, gateway_id, dashboard_id}` with sealed `locate.id`.
- **Size:** ~5.9k.

## 18. Bot Mode

- **Behaviour:**
  - A bot is a Hermes profile. Roster grid; create or edit with name, description ("What this bot is for"), soul, photo and colour; delete.
  - Each bot has one canonical "Bot Chat" session, found by exact title.
  - @mentions of teammates.
- **Files:** `Services/BotManagement.swift` (454), `BotModeModels.swift` (456), `BotMentions.swift` (299); `Views/BotRosterView.swift` (689), `BotEditorSheet.swift` (461), `V2/V2BotsView.swift` (61); AppState §Bot Mode L1118–1571 and L15811–16311.
- **Wire:**
  - RPC `profiles.list` (unscoped; also the capability probe), `profiles.create {name, description, soul}`, `profiles.describe {name}`, `profiles.configure {name, description?, soul?, ui_meta:{"hermes-bots":…}, ui_meta_expected_revisions}`, `profiles.get_asset` / `set_asset {name, asset:"avatar", data | clear}`.
  - `session.list` (exact-title lookup), `session.create {profile}` (title "Bot Chat").
  - `DELETE /api/profiles/{name}`; `GET /api/profiles`.
- **Size:** ~3.4k.

## 19. Group chats (hosted rooms)

- **Behaviour:**
  - Multi-bot rooms: create with members, send, threads, a live activity feed per member, stop, approve a member's tool request (Run / Reject), rename events, disband.
  - Group picture; files and images in rooms; group composer with attachments.
- **Files:** `Services/GroupChatModels.swift` (1074), `GroupRoomFiles.swift` (97), `GroupRoomPictures.swift` (204); `Views/GroupChatView.swift` (1299), `Components/GroupRoomComposer.swift` (628); AppState §Group Chats L7644–9000.
- **Wire:**
  - RPC (all unscoped): `groups.capabilities` (`protocol_version`, method list), `groups.list`, `groups.create {room_id, name, members}`, `groups.state {room_id, include_disbanded}`, `groups.send {room_id, event_id, payload:{text, thread_id}}`, `groups.log {room_id, since_seq, include_disbanded}`, `groups.stop`, `groups.disband`, `groups.approve {room_id, request_id, member_id, task_id, execution_generation, choice}`, `groups.rename`, `groups.retry`.
  - Events: `message.user`, `message.member`, `room.activity`, `room.members_changed`, `room.renamed`, `room.disbanded`, `room.stop_requested`, `member.unavailable`, `turn.settled`, `turn.failed`, `turn.cancelled`.
  - REST: `PLUGIN/room-files`, `PLUGIN/room-images`.
- **Size:** ~4.7k.

## 20. Kanban

- **Behaviour:**
  - Boards: create, edit (slug, description, project scope, default workspace), archive.
  - Lanes `triage, todo, scheduled, ready, running, blocked, review, done, archived`; filters (show archived, group Running by assignee).
  - Task composer: title, description, workspace override, dependencies, skills, model override.
  - Task detail: comments; "Note & requeue"; "Reply & unblock"; reassign or reclaim; Specify Task (AI); Decompose Into Tasks (AI); Copy ID or title; archive; delete; worker log.
  - Bulk select, move, assign and delete.
  - Orchestration settings; profile routing descriptions (with "Generate Automatically").
  - Live updates over WebSocket.
- **Files:** `Models/KanbanModels.swift` (1346), `KanbanStatus.swift` (96); `Services/KanbanService.swift` (510), `KanbanStore.swift` (1145), `KanbanEventStream.swift` (645); `Views/KanbanViews.swift` (2156); `Views/Kanban/*` (KanbanBoardAdministrationSupport 379, KanbanBoardEditorView 453, KanbanBoardFilterSheet 84, KanbanBulkOperationSupport 203, KanbanLiveUpdateSupport 46, KanbanOrchestrationSettingsSheet 247, KanbanProfileRoutingScreen 364, KanbanTaskComposerView 829, KanbanTaskDetailView 1787, KanbanV2Support 632, KanbanV3ASupport 328, KanbanWorkerLogScreen 199); V2TasksView.
- **Wire** (prefix `/api/plugins/kanban`, `?board=<slug>`):
  - Boards: `GET /boards`, `POST /boards`, `PATCH /boards/{slug}`, `DELETE /boards/{slug}`, `GET /board?include_archived=`.
  - Tasks: `POST /tasks`, `GET` / `PATCH` / `DELETE /tasks/{id}`, `GET /tasks/{id}/log?tail=16384`, `POST /tasks/{id}/comments`, `/reassign`, `/reclaim`, `/specify`, `/decompose`, `POST /tasks/bulk`.
  - Profiles and projects: `GET /profiles`, `PATCH /profiles/{p}`, `POST /profiles/{p}/describe-auto`, `GET /projects`.
  - Orchestration and dispatch: `GET` / `PUT /orchestration`, `GET /model-options`, `POST /dispatch`.
  - Live updates: WS `/api/plugins/kanban/events?ticket=&board=&since=<cursor>`.
- **Size:** ~11.4k. This is the largest single area.

## 21. Artifacts

- **Behaviour:**
  - Per-profile and per-chat list of files Hermes sent or wrote, HTML pages, user attachments and links.
  - Filter: all / images / pages / files / links. Opens Quick Look, the HTML viewer or the browser.
- **Files:** `Services/Artifacts.swift` (194), `AppState+Artifacts.swift` (79); `Views/V2/V2ArtifactsViews.swift` (658).
- **Wire:** `PLUGIN/artifacts` (capability `"artifacts"`, plugin 0.40+); kinds `image, video, audio, page, document, file, link`; origins `delivered, preview, written, attached, link`.
- **Size:** ~930.

## 22. Presented screens (Hermes asks the phone to confirm)

- **Behaviour:** Hermes shows a confirm panel (title, preview, action label). The user taps, or says yes during a live call. The answer goes back to the `conduit_present` tool.
- **Files:** `Services/PresentedScreens.swift` (374), `AppState+PresentedScreens.swift` (182), `Views/PresentedConfirmPanel.swift` (158).
- **Wire:** `POST PLUGIN/present/exchange` `{"installation_id","device","app_version","results":[{"id","outcome","via"}],"features"}`.
- **Size:** ~710.

## 23. Ask Hermes About Screen

- **Behaviour:** an Action Button / Shortcuts action takes a screenshot. It waits on a chat as a "pending screenshot" and goes with the next turn. Settings page includes an install link for the shortcut.
- **Files:** `Intents/AskHermesAboutScreenIntent.swift` (197; parameters Question, Start with, Profile, Chat), `Services/AppState+ScreenQuestion.swift` (632), `Views/ScreenQuestionViews.swift` (208), `AskHermesPromptView.swift` (98).
- **Wire:** `image.attach_bytes` + `prompt.submit`; shortcut file `https://kaishi00.github.io/hermes-conduit-notifier/shortcuts/ask-hermes-about-screen.json`.
- **Size:** ~1.1k. On Android this could be a share target or the assist / screenshot intent.

## 24. Shortcuts / App Intents (Android analog: App Actions, share targets, QS tiles)

- **Send to Hermes:** text to a chat in the background. Parameters "Wait for Answer" (default true; returns the reply), "Chat Name", "Profile". About 30 s budget. `Intents/SendToHermesIntent.swift` (346), `Services/AppState+ShortcutSend.swift` (84).
- **Start Voice Conversation:** `StartVoiceConversationIntent.swift` (132) with `AppShortcutsProvider` phrases.
- **Focus filter:** `HermesFocusFilter.swift` (58). Parameters "Name for Hermes" and "Hold calls from Hermes".
- **Deep links:** `Services/ConduitAppLink.swift` (140).
- **Wire:** same gateway RPCs.
- **Size:** ~760.

## 25. Home Screen widgets

- **Behaviour:** Native and "Conduit-chan" launcher widgets. Per-widget profile and chat choice; actions `open`, `resume`, `newChat`, `talk`. Snapshot of recent chats.
- **Files:** `ConduitWidgets/LauncherWidgets.swift` (180), `LauncherTimelineProvider.swift` (40); `Shared/Widgets/WidgetConfiguration.swift` (223), `WidgetLaunchLink.swift` (134), `WidgetSelection.swift` (104), `WidgetSnapshot.swift` (179), `WidgetSnapshotStore.swift` (41), `WidgetLocalization.swift` (32); `Services/AppState+Widgets.swift` (156), `AppState+WidgetLaunch.swift` (130), `WidgetLaunchCoordinator.swift` (308), `WidgetSnapshotPublisher.swift` (64).
- **Wire:** local app group.
- **Size:** ~1.6k. Android: Glance widgets.

## 26. Apple Watch (Android analog: Wear OS)

- **Behaviour:**
  - The Watch app talks to Hermes: Gemini Live directly on the Watch with a phone broker, or GPT-Live / Grok on the host via a bridge socket.
  - Complication and Control widget; call log.
  - Phone-side settings: Approve from the Watch, Call log share/clear, GPT-Live on the Watch runtime setup.
  - Tool grants.
- **Files:**
  - Watch app: `ConduitWatch/*` (about 8.6k; WatchDirectCallModel 3352, WatchBridgeCallModel 1903, WatchAudio 421, WatchVoiceCall 413, HermesWatchCalls 474, sockets 302/325/157, …).
  - Phone side: `Conduit/Watch/*` (about 2k; WatchDirectBroker 1400, WatchSettingsSection 215, WatchToolGrantClient 132, WatchVoiceLink 171).
  - Shared: `Shared/WatchVoice/*` (about 3.2k); `Views/LiquidOrb`.
- **Wire:** `PLUGIN/watch-tools/grant`, `/watch-tools/revoke`, `/watch-audio/prepare`, `/watch-audio/status`; relay `v1/watch-tools/grants/{id}`, `/v1/rings/{id}/settled`.
- **Size:** ~14k. Port last.
- **Wear OS port (`android/wear`, phone side `app/.../watch/`, shared wire `android/wearwire`):**
  - Bridge call only: the watch recognizes speech, sends each utterance over the Data Layer (`/conduit/voice/turn`), the phone's `WatchBridgeService` runs it with `prompt.submit` (surface `voice`, `voice_turn`) and streams the reply back (`/conduit/voice/reply`), which the watch speaks with TextToSpeech. Tap the orb to interrupt (`session.interrupt`).
  - Home: Talk to Hermes, recent chats (continue one by voice), pending approvals (Approve once / Deny), call log, Call on open. Tile, complication, `conduitwatch://talk` and a static shortcut open straight into a call.
  - Phone: Settings > Watch (`settings.watch`): watch status, Approve from the Watch (Tap / Tap or voice), a watch-tools grant/revoke check, call log share/clear.
  - Not ported: direct Gemini Live on the watch and GPT-Live/Grok audio bridges (WatchDirectCallModel, WatchGeminiSocket, WatchGrokBridgeSocket, WatchDirectBroker, watch-audio runtime setup). They follow the phone's live-voice port (§6); `WatchToolGrantClient` is ported for that path. Jobs per Watch call (relay jobs) belong to the direct path too.

## 27. CarPlay (Android analog: Android Auto)

- **Behaviour:** voice-first CarPlay: browse chats, voice conversation with earcons, state templates, artwork. Settings "Choose a chat first" and "Status sounds".
- **Files:** `Conduit/CarPlay/*`: CarPlayVoiceCoordinator 1800, CarPlayBrowseTemplates 452, CarPlayVoiceArtwork 342, CarPlayVoiceTemplateFactory 186, CarPlayVoiceStateMapping 164, CarPlayPreferences 101, CarPlayEarcons 93, plus small files; `Views/CarPlaySettingsSection.swift` (127).
- **Wire:** reuses voice.
- **Size:** ~3.4k.

## 28. Offline chat cache

- **Behaviour:**
  - Read-only on-disk copy of recently opened chats, shown on a cold launch or when the server is unreachable, with a "Retry" banner.
  - Includes a session presentation cache and an outbox for unsent items (`unsent.json`).
- **Files:** `Services/OfflineChatCache.swift` (352), `Views/OfflineChatTranscriptView.swift` (96), `SessionPresentationCache.swift` (1699), AppState §Offline chat cache L3713–3889.
- **Wire:** none.
- **Size:** ~2.3k.

## 29. Theming, V2 glow and haptics

- **Behaviour:**
  - Dark / Light / System theme.
  - Gold accent design system (`conduitAccent`, `conduitAura`); glass controls.
  - V2 animated backdrop glow per profile: picked colour, from the photo, or the bot colour. Breathing agent mark.
  - Haptics engine with a toggle.
- **Files:** `Conduit/Theme.swift` (511), `Services/ProfileGlowColors.swift` (216), `ProfileAppearanceStore.swift` (423), `Haptics.swift` (493), `Views/Components/DynamicTypeSupport.swift` (88), `ConduitSheetHeader.swift` (33), `ChatView.swift` `ConduitAgentMark` / `ConduitBreathing`, `V2/V2Shell.swift` (V2Backdrop).
- **Size:** ~1.8k.

## 30. Localization

- **Behaviour:** in-app language picker independent of the system. 14 locales: `de, en, es, fr, id, it, ja, ko, pl, pt-BR, ru, tr, zh-Hans, zh-Hant`. 3,155 strings. Every string goes through `AppLocalization.string`.
- **Files:** `Conduit/Localizable.xcstrings`, `Services/AppLanguage.swift` (279).
- **Android:** per-app locales (`AppCompatDelegate.setApplicationLocales`) plus `strings.xml` exports for 13 languages.

## 31. App icons

- **Behaviour:** alternate icons `dark` / `light`.
- **Files:** `Models/AppIconChoice.swift` (23), AppState `selectAppIcon`.
- **Android:** `activity-alias` switching. Small.

## 32. Misc. infrastructure behind user-visible behaviour

- **Conversation identity and session references** (durable vs runtime ids, aliases): `ConversationIdentity.swift` (160), `ConversationIdentityIndex.swift` (247), `SessionReference.swift` (288), `HermesSessionReference.swift` (222), `SessionMutationIdentity.swift` (83), `SessionRenameOperation.swift` (127). About 1.1k lines. Needed for push routing, widgets and voice.
- **Transcript performance instrumentation:** `TranscriptPerformanceInstrumentation.swift` (558). Optional.
- **Session list profile fan-out and secondary-profile title recovery:** AppState L24068.
- **`approval.pending` re-hydration on resume:** `HermesClient.pendingApprovals` L2417. Android only shows live approval events.
- **Clarify history records:** `Models/ClarifyHistoryRecord.swift` (139). Shows answered clarifies in the transcript.

---

## Suggested porting order

Ordered by user impact divided by effort:
1. Composer extras: attachments (`image.attach_bytes` / `file.attach`), reasoning / fast / YOLO, slash commands, compress, context sheet, steer / interrupt setting, drafts.
2. Message actions: copy, branch, quote, reactions, read-aloud (`/api/audio/speak-stream`). Masked input prompts.
3. Sidebar: search, filters, archived sheet, unread, projects, auto-titles, `session.active_list` badges.
4. Settings pages that use the generic `/api/config` engine (Chat, Workspace, Memory, Model defaults, Delegate model), plus Gateway diagnostics, logs and restart, custom headers, Appearance, language.
5. Cron, Capabilities, then Kanban.
6. Native OAuth, saved dashboards (with BiometricPrompt), offline cache.
7. Conduit plugin management, then FCM push. This needs relay work: `/v1/installations` is APNs-only.
8. Classic voice and dictation, then Gemini Live (WebSocket), Grok (WebSocket), GPT-Live (WebRTC).
9. Bot Mode, then Group chats.
10. Artifacts, presented screens, chat takeover, Hermes calls (FCM plus ConnectionService).
11. Device data sync: Health Connect, Location, Calendar/Contacts.
12. Platform integrations: widgets (Glance), App Actions / share target, Wear OS, Android Auto, wake phrase (sherpa-onnx has Android builds).
