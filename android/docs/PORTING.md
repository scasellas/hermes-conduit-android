# Porting guide (iOS → Android)

The Android app mirrors the iOS app in `../Conduit`. When porting a feature,
read the Swift implementation first and keep the same wire protocol, the same
user-facing English strings (they are localization keys), and the same
behaviour. Simplify UI chrome where Android idioms differ, never the semantics.

## Architecture

- `core/HermesConnection` (one per app, `context.hermes`): the active
  dashboard. Use it from feature view models:
  - `hermes.rest { api -> api.getJson("api/cron/jobs", mapOf("profile" to p)) }`
    (re-logs in once on an expired session). `api.sendJson(method, path, body, query)`,
    `api.getBytes(path, query)`. Responses that are top-level arrays arrive as
    `{"_array": [...]}`.
  - `hermes.rpc("method", buildJsonObject { ... })` for the gateway socket.
    The active profile is injected automatically (except for `default`).
  - `hermes.events` (SharedFlow<StreamEvent>), `hermes.connected`, `hermes.link`,
    `hermes.profile`, `hermes.connection` (SavedConnection).
  - REST profile scoping: add `"profile" to profile.takeIf { it != "default" }`
    to the query unless the Swift code says otherwise.
  - Plugin routes live under `api/plugins/conduit_push/...`.
- `data/AppSettings` (`context.appSettings`): device-local preferences as
  StateFlows. Add keys to its companion.
- `model/`: JSON helpers (`obj`, `arr`, `str`, `bool`, `long`, `double`,
  `firstString`), wire models and parsers. Keep parsers pure and unit-tested.
- `ui/nav/Features.kt`: every pushed screen is one `Feature(route, title, icon)`
  entry. `inDrawer = true` shows it in the drawer's tool strip; routes that
  start with `settings.` are listed on the Settings page. A route can take one
  argument: navigate to `"route/${Uri.encode(arg)}"` and read `LocalRouteArg.current`.
  `LocalNavigate.current("route")` opens a route from anywhere.
- `ui/components/ScreenScaffold.kt`: `ScreenScaffold`, `SettingsColumn`,
  `SettingsSection`, `SettingsRow`, `SettingsToggle`, `LoadingBox`,
  `EmptyState`, `ErrorText`. Use them so screens look consistent.
- Strings: wrap every user-visible string in `t("English text")` (composable)
  or `L10n.tr(...)` (non-composable). Use the iOS wording verbatim when it
  exists so the iOS translations apply; format args use `%s`/`%d`.

## Conventions

- Kotlin, Jetpack Compose, Material 3, coroutines. No new DI framework.
- One feature per package under `ui/<feature>/` (screen + view model) and
  `model/<Feature>Models.kt` for parsing.
- View models: `AndroidViewModel`, state in `MutableStateFlow`, errors shown
  inline (never crash on a malformed payload — the gateway varies by version).
- Add JVM unit tests in `app/src/test/...` for parsers and reducers.
- Build and test before committing:
  `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain`
  (pass `-x verifyPaparazziDebug` is not needed; Paparazzi only runs on verify).
- Don't touch files owned by other features unless needed; when you must add a
  line to `Features.kt`, `AppSettings.kt` or `HermesConnection.kt`, keep it minimal.
