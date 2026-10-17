# Android push through UnifiedPush: what the notifier plugin adds

Status: the Android client is done (Conduit for Android, `push/`, `model/PushE2E.kt`). This document is the server half: everything the Hermes notifier plugin (`conduit_push`) must add so it can wake an Android phone while Conduit is closed. It is meant to be implementable from this document alone.

On iOS the plugin sends pushes through the push relay (`push.milim.dev`), which only talks to APNs. Android does not use the relay at all. Instead the phone registers a [UnifiedPush](https://unifiedpush.org) endpoint (an HTTPS URL on ntfy, NextPush, Sunup, a self-hosted server and so on) with the plugin, and the plugin POSTs sealed messages straight to that URL.

**Never send anything for an Android registration to `push.milim.dev` or any other relay.** The only outbound request is the POST to the registration's own `endpoint`.

Until the plugin has these routes, Conduit for Android gets notifications, calls and locates only while its background connection is up, and Settings > Notifications says "Your Hermes notifier doesn't support Android push yet".

## 1. Conventions

- `PLUGIN` is the plugin's dashboard route prefix: `/api/plugins/conduit_push`.
- Every route is scoped to a Hermes profile with the `profile` query parameter, as all the plugin's routes already are (iOS `DashboardPath.withProfile`). The parameter is left out for the `default` profile. Registrations are stored per profile.
- Requests arrive through the dashboard with the user's dashboard session (cookie or OAuth bearer), the same authentication as the plugin's other routes. The plugin never authenticates the phone any other way.
- All bodies are JSON (`Content-Type: application/json`). Unknown fields must be ignored.
- base64url means RFC 4648 section 5 **without padding**.
- Times are integer Unix seconds unless stated otherwise.

## 2. Capability

Add `"android-push"` to the list `GET PLUGIN/capabilities` returns. The app does not depend on it (it probes the route itself, see 3.3), but it lets Settings and the docs say which plugin version supports Android.

## 3. Routes

### 3.1 `POST PLUGIN/push/android/register?profile=<name>`

Creates or replaces this profile's registration for one phone. The phone sends it every time it connects to the dashboard, when the distributor gives it a new endpoint, and after the user changes profile. It must be idempotent: the same body twice gives the same result.

Request body:

```json
{
  "installation_id": "5f0c1b9e-8a8c-4b8e-9a43-2c1d7a0b6f11",
  "endpoint": "https://ntfy.sh/upAbCdEf0123456789?up=1",
  "platform": "android-unifiedpush",
  "preferences": {
    "enabled": true,
    "approval_needed": true,
    "input_needed": true,
    "response_ready": true,
    "turn_failed": true,
    "background_task_finished": true,
    "completion_sound": false,
    "attention_sound": true,
    "show_previews": true,
    "decision_cards": true
  },
  "e2e": {
    "v": 1,
    "kid": "0123456789abcdef0123456789abcdef",
    "secret": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
  },
  "webpush": {
    "p256dh": "BOr8…(87 characters)…",
    "auth": "kq3Ufn1Kz9Q2xZ8pYw4N_g"
  },
  "app_version": "0.1.0",
  "device": "Google Pixel 8"
}
```

| Field | Type | Rules |
|---|---|---|
| `installation_id` | string | Lowercase UUID the phone made once. Identifies the phone across profiles and dashboards (the iOS relay installation id plays this part on iPhone). 1 to 64 characters of `[0-9a-f-]`. |
| `endpoint` | string | The UnifiedPush endpoint URL. `https://` (also accept `http://` only when the operator allows it, for a LAN push server). At most 2048 characters. Treat it as a secret: anyone who has it can push to the phone. Never log it in full. |
| `platform` | string | Always `"android-unifiedpush"`. Reject anything else with 400. |
| `preferences` | object | Exactly the iOS `ConduitNotificationPreferences` keys. Missing keys default as on iOS: every flag `true` except `show_previews` (`false`). They gate events as in section 4. |
| `e2e.v` | number | `1`. |
| `e2e.kid` | string | 32 lowercase hex characters. |
| `e2e.secret` | string | base64url of exactly 32 random bytes: the root secret of this registration's end-to-end key, the same thing iOS posts to `PLUGIN/e2e`. |
| `webpush` | object, optional | RFC 8291 keys the phone's UnifiedPush connector generated: `p256dh` (base64url, uncompressed P-256 point, 65 bytes) and `auth` (base64url, 16 bytes). See 6.3. |
| `app_version` | string | Conduit's version, for display and diagnostics. |
| `device` | string | Manufacturer and model, for display ("Google Pixel 8"). |

Behaviour:

1. Validate the body. Any violation: `400` with `{"ok": false, "error": "<reason>"}`. **Never answer 404 for a valid request**: 404 is how the app recognises a plugin without this route.
2. Look up the registration by `(profile, installation_id)`. Create it, or replace every field of the existing one: endpoint, preferences, key, Web Push keys, app version, device. A new `kid` replaces the old key immediately; the old one must not be used again.
3. Assign `gateway_id`: an opaque, stable id for this profile's pairing, at most 64 characters of `[A-Za-z0-9_-]`. Use the profile's relay gateway id when the profile is paired with the relay, otherwise generate a UUID once per profile and keep it. It is part of the sealed messages' associated data (section 5), so it must be the value the plugin uses when sealing for this registration.
4. Reset the registration's delivery failure state (sections 6.5 and 7).
5. Answer `200`:

```json
{ "ok": true, "installation_id": "5f0c1b9e-8a8c-4b8e-9a43-2c1d7a0b6f11", "gateway_id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "kid": "0123456789abcdef0123456789abcdef" }
```

The phone keeps the key only when `ok` is `true`, `kid` echoes the one it sent and `gateway_id` is present (as iOS keeps a key only once `POST /e2e` echoes its `kid`). Optional extra fields are ignored.

Limits: at most 10 registrations per profile. When an 11th installation registers, drop the one that was updated longest ago.

### 3.2 `POST PLUGIN/push/android/unregister?profile=<name>`

```json
{ "installation_id": "5f0c1b9e-8a8c-4b8e-9a43-2c1d7a0b6f11", "platform": "android-unifiedpush" }
```

Deletes the registration `(profile, installation_id)` with its key. Idempotent: answer `200 {"ok": true, "removed": true|false}` whether or not it existed. The phone calls it when the user turns push off; it is best effort, so the plugin must also drop registrations whose endpoint answers 404 or 410 (section 7).

### 3.3 How the app probes

The app POSTs `register` directly. `404` means "this plugin can't send Android pushes": the app shows "Your Hermes notifier doesn't support Android push yet", keeps using its background connection, and tries again at the next connect. Any other error is shown as a failure and retried at the next connect.

### 3.4 Status (optional)

The existing `GET PLUGIN/e2e?profile=` status, if extended, may list Android registrations as `android: [{installation_id, device, app_version, kid, last_success_at, failing}]` for diagnostics. The app does not read it yet.

## 4. Which events go to an Android registration

For every event the plugin already turns into an iOS push, also deliver it to each Android registration of the event's profile whose preferences allow it:

| Event `type` | Gate | Sealed content (section 5) |
|---|---|---|
| `approval.needed` | `enabled` and `approval_needed` | `title`, `body`, `session_id`, `profile`, `stored_session_id` if known, `decision` (approval kind, only when `decision_cards`) |
| `input.needed` | `enabled` and `input_needed` | `title`, `body`, `session_id`, `profile`, `stored_session_id` |
| `response.ready` | `enabled` and `response_ready` | same |
| `turn.failed` | `enabled` and `turn_failed` | same |
| `background_task.finished` | `enabled` and `background_task_finished` | same |
| `call.requested` | always (the host's own call rules already apply) | `session_id`, `profile`, `stored_session_id`, `call` |
| `location.requested` | always (the phone decides) | `profile`, and `locate: {id}` or `maps: {id}` |

Content fields are exactly what the plugin seals for iOS today:

- `title`, `body`: the real text. Include them even when `show_previews` is false; the phone decides what it shows. They may be shortened to fit (section 6.2).
- `decision` for `approval.needed`: `{"kind": "approval", "session_key": "<session to answer>", "description": "<what is asked>", "choices": ["once", "session", "always", "deny"]}`. The phone answers on its own socket (`approval.respond {choice, session_id}`), not through the relay. Clarify decisions (`kind: "clarify"` with a `conduit-push-…` request id) are answered through the relay on iOS, which Android has no access to: leave them out for Android registrations.
- `call` for `call.requested`: `{"id", "kind", "title", "session_ids", "reason"}` as in the VoIP push. Optionally `sent_at` (Unix seconds) at the top level of the content; without it the phone uses the envelope's `iat`.
- `locate.id` matches `^[A-Za-z0-9_-]{4,80}$`; `maps.id` matches `^m_[A-Za-z0-9_-]{16,64}$`. Send a `location.requested` push to the Android registrations of the profile the location device is bound to. The phone answers exactly as the iOS Location Push extension does: a locate with `POST PLUGIN/location/ingest` (sealed batch carrying `locate_id`), a maps request with `GET PLUGIN/location/maps/{id}?device_id=&kid=` and then `POST PLUGIN/location/maps/{id}`. A phone that can be woken advertises `"maps"` in its batches' `sharing.features`.

Anything else the plugin wants the phone to notice (a kanban change, a background job the phone tracks) can be sent as a plain wake-up (section 6.4).

## 5. Sealing (byte-compatible with iOS)

Use the plugin's existing `e2e.seal_event`, with this registration's key:

- `installation_id` = the registration's `installation_id`;
- `gateway_id` = the `gateway_id` returned by `register`;
- `kid`, `secret` = the registration's `e2e`.

For reference, the construction:

1. Keys: `HKDF-SHA256(ikm = secret, salt = "conduit-e2e-v1", info = "conduit-e2e-v1 push gateway-to-device", L = 32)`. (The answer key, info `"conduit-e2e-v1 answer device-to-gateway"`, is not used on Android yet.)
2. Plaintext: the content object as compact UTF-8 JSON. If raw DEFLATE (RFC 1951, no zlib header, `wbits = -15`) makes it smaller, use the compressed bytes and set `z = 1`, otherwise `z = 0`.
3. Fields: `msg` is a new id per event, unique for at least 48 hours (for example `"<type>:<32 hex>"`, at most 128 characters); `iat` = now; `tok` = 16 random lowercase hex characters; `req` = the decision's request id, or `""`.
4. Associated data, lines joined with `\n` (no trailing newline):

```
conduit-e2e/1
push
kid=<kid>
inst=<installation_id>
gw=<gateway_id>
msg=<msg>
type=<type>
iat=<iat>
tok=<tok>
z=<z>
req=<req>
```

5. ChaCha20-Poly1305 (RFC 8439) with a random 12-byte nonce; `ct` is ciphertext followed by the 16-byte tag.

The message body POSTed to the endpoint is the iOS push's JSON without `aps`:

```json
{
  "conduit": { "type": "input.needed", "e2e": 1 },
  "conduit_e2e": {
    "v": 1,
    "kid": "0123456789abcdef0123456789abcdef",
    "msg": "input:9ab3405eb5333adb44e353fcd81508e8",
    "iat": 1760000000,
    "tok": "5d671a3554c84fb7",
    "z": 1,
    "req": "conduit-push-abc123def456",
    "n": "BwcHBwcHBwcHBwcH",
    "ct": "YEtXcEZp_hUYxXaIBmF-kC5gT4i7IomTbFRQmwF8LiFNeAzHOrZSd-VdKXI8WBkdjNd9GCKsCqePYurZuUJCINjnT2wiVZAGyBa2rq2UZfKo9399Ql8p-f4Rywl3wgNKh33TNk8pGP0EvdOuI6zi3Haw-c1-bCrheVS1BkufqRdPe2WPDPElQCiw0RU6PRCeEwLbFd7G30C0RKQDmExz-ZyYrQ"
  }
}
```

`conduit` (the routing stub) carries only `type` and `e2e: 1`. Do not put a session id, profile, dashboard id, gateway id or text in it: everything the phone acts on comes from the sealed content and from the key's own registration, never from the stub. The phone rejects an envelope whose `iat` is more than 24 hours old or more than 1 hour in the future, and handles each `kid:msg` once.

Test vector (the same one `ConduitTests/NotificationE2ETests.swift` and `PushE2ETest.kt` use): `kid = 0123456789abcdef0123456789abcdef`, `secret = bytes 0x00…0x1f`, `installation_id = 11111111-2222-3333-4444-555555555555`, `gateway_id = aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee`, nonce `07` × 12, `type = input.needed`, the fields above. The derived push key is `a5a8884943a780bbf4f846cf294f149d8c668f58312ca26841ec7f78e5a1a099`; the content opens to title "Hermes", body "Deploy to prod?", `session_id` "sess-1", profile "coder" and a clarify decision for `conduit-push-abc123def456`.

## 6. Delivering to the endpoint

### 6.1 The request

```
POST <endpoint>
Content-Type: application/json          (application/octet-stream with Web Push, 6.3)
TTL: <seconds>
Urgency: high | normal
```

- `TTL`: 60 for `call.requested`, 30 for `location.requested`, 86400 otherwise.
- `Urgency: high` for `approval.needed`, `input.needed`, `call.requested`, `location.requested`; `normal` for the rest. Push servers that know RFC 8030 use these; others ignore them.
- Connect timeout 10 s, whole request 15 s. Follow no redirects.
- Verify TLS as usual. Send no cookies or credentials of any kind: the endpoint URL is the only authorisation.
- Success is any `2xx` (ntfy answers 200, Web Push servers 201).

### 6.2 Size: 4096 bytes

The POSTed body must be at most **4096 bytes** (the size every UnifiedPush distributor guarantees). With Web Push encryption the plaintext JSON may then be at most 3993 bytes (4096 minus the 86-byte aes128gcm header, the 16-byte tag and the 1-byte padding delimiter). To fit:

1. Seal with compression (`z = 1`) when it helps (section 5 step 2).
2. Still too large: shorten `body` to 400 characters, then drop `decision`, then shorten `body` to 120 characters, sealing again each time (a new nonce each time, the same `msg`).
3. Still too large: send a plain wake-up (6.4) with `"reason": "too_large"`. The phone reconnects and finds the event itself.

### 6.3 Web Push encryption (optional, recommended)

When the registration has `webpush` keys, encrypt the body with RFC 8291 (`aes128gcm`, RFC 8188, one record, record size 4096) using `p256dh` and `auth`, and send `Content-Encoding: aes128gcm` with `Content-Type: application/octet-stream`. Some push servers (Mozilla autopush, and so Sunup) refuse unencrypted bodies; with ntfy it hides even the routing stub from the server. A VAPID header is not needed: the phone registers without a VAPID key. Without `webpush`, send the JSON as is; the connector passes plaintext bodies through.

### 6.4 Plain wake-up

```json
{ "conduit": { "type": "wake", "reason": "too_large" } }
```

No envelope. The phone treats any message it can't verify the same way: it reconnects its gateway socket and runs one sweep of active sessions and pending approvals, showing only what it finds there. `reason` is free text for logs (`too_large`, `no_key`, `kanban`, …).

### 6.5 Retries and dropping registrations

| Endpoint answer | What the plugin does |
|---|---|
| `2xx` | Done. Record `last_success_at`, reset the failure count. |
| `404`, `410` | The endpoint is gone (the distributor unregistered). Delete the registration and its key at once; never retry. |
| `413` | Send the plain wake-up (6.4) once instead; never retry the large body. |
| `429` | Retry after `Retry-After` (seconds or HTTP date), capped at 10 minutes, if the message's TTL still allows. |
| `5xx`, timeout, connection error | Retry with exponential backoff (2 s, 4 s, 8 s, 16 s, 32 s, with ±20 % jitter), at most 5 attempts, never past the message's TTL. |
| other `4xx` | Drop this message and count a failure; don't retry. |

Retries send the **same bytes** (same `msg`, nonce and ciphertext): the phone handles each `kid:msg` once, so a retry that raced a slow success is harmless. Deliveries for one registration go out one at a time, in event order.

After 7 days without a success and at least 20 failed messages, delete the registration. The phone registers again the next time it connects.

## 7. Storage

One record per `(profile, installation_id)`:

| Field | Notes |
|---|---|
| `installation_id`, `platform` | key |
| `endpoint` | secret; store like other credentials, log only its host |
| `webpush_p256dh`, `webpush_auth` | optional |
| `kid`, `secret` | the end-to-end key; protect at rest like the iOS `e2e` keys |
| `gateway_id` | as returned by `register` |
| `preferences` | the object as sent |
| `app_version`, `device` | display |
| `created_at`, `updated_at`, `last_success_at` | Unix seconds |
| `failure_count`, `first_failure_at` | for section 6.5 |

Deleting a profile deletes its registrations. `unregister`, a 404/410 from the endpoint, and the 7-day rule delete one.

## 8. Checklist

- [ ] `POST PLUGIN/push/android/register` and `/unregister`, per profile, never 404 for valid requests.
- [ ] `"android-push"` in `GET PLUGIN/capabilities`.
- [ ] Events of section 4 sealed with `e2e.seal_event` using the Android registration's installation id, gateway id and key.
- [ ] Routing stub with `type` and `e2e: 1` only.
- [ ] POST to the endpoint, ≤ 4096 bytes, Web Push encryption when keys are given, the wake-up fallback.
- [ ] Retries and dropping registrations as in 6.5.
- [ ] Nothing for Android goes to `push.milim.dev`.
- [ ] Unit test against the vector in section 5.
