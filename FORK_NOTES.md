# Fork: self-hosted server support

This is a fork of [stoatchat/for-android](https://github.com/stoatchat/for-android) that adds
a GUI flow for connecting the app to a **self-hosted Stoat/Revolt instance** instead of the
official first-party servers.

## How it works for users

On the login greeting screen, tap **"Use a self-hosted server"** (below the terms/privacy
links). Enter your instance's API URL (e.g. `https://api.example.com` — a bare domain or a
`/api` path also works) and tap **Connect**. The app fetches the instance's self-describing
configuration from the API root and automatically configures:

- the API base URL (what you entered)
- the websocket / events server (`ws` field)
- the file server / CDN (`features.autumn.url`)
- the media proxy (`features.january.url`)
- the web app URL (`app` field, used for links the app generates)

The choice is persisted and survives app restarts. The same screen shows which server is
currently in use and offers **"Reset to official server"**. Change servers while logged out;
sessions are per-instance.

## Architecture / where the code lives

Nearly all fork logic is in **new files**, which can never produce merge conflicts with
upstream:

| File | Purpose |
|---|---|
| `core/model/.../data/EndpointConfig.kt` | Runtime-mutable endpoint config; official URLs as defaults |
| `app/.../selfhost/SelfHostedEndpoints.kt` | Persistence (KVStorage/DataStore) + hydration |
| `app/.../screens/login/SelfHostedServerScreen.kt` | The GUI flow (screen + ViewModel) |
| `app/.../c2dm/PushMessageRenderer.kt` | Notification rendering, extracted from `HandlerService` so FCM and UnifiedPush share it. Does blocking I/O (Glide `.get()`, `runBlocking` REST/DB) — callers must invoke it off the main thread |
| `app/.../unifiedpush/StoatUnifiedPushService.kt` | UnifiedPush receiver: endpoint registration + payload parsing. **The connector calls `onMessage`/`onNewEndpoint` on the main thread**, so all work is dispatched to a background `Dispatchers.IO` scope — do not add blocking calls directly in the callbacks (ANR risk) |
| `app/.../unifiedpush/UnifiedPushManager.kt` | Registers/unregisters a UnifiedPush distributor; respects the `pushNotificationsRejected` opt-out; skips registration when background-socket push is enabled |
| `app/.../services/ForegroundSocketService.kt` | **Background-socket push**: foreground service that keeps the realtime websocket alive when backgrounded and renders notifications directly from message frames — no Google, no distributor, no backend push. `specialUse` FGS type (no Android 15+ runtime cap). Evaluates notification settings, direct/role/mass mentions on-device |
| `app/.../services/AppVisibility.kt` | App foreground/background tracker (activity lifecycle callbacks) so the socket service only notifies while backgrounded |
| `app/.../services/BootReceiver.kt` | Restarts the socket service after reboot when opted in |
| `selfhost/` | Backend patch + instructions for aes128gcm web push (UnifiedPush prerequisite) |
| `.github/workflows/sync-upstream.yml` | Daily upstream sync automation |
| `.github/workflows/build-fork.yml` | Debug APK builds |
| `.github/workflows/build-pushd.yml` | Builds patched backend pushd image to GHCR |

**Modified upstream files** (kept as small as possible — these are the only places a sync
conflict can involve fork code):

| File | Change |
|---|---|
| `core/model/.../data/Constants.kt` | `STOAT_BASE`, `STOAT_WEBSOCKET`, `STOAT_FILES`, `STOAT_PROXY`, `STOAT_WEB_APP`, `STOAT_INVITES` changed from `const val` to getters delegating to `EndpointConfig` |
| `core/model/.../schemas/Invites.kt` | `isInviteUri` also matches the official `stt.gg` host when on a self-hosted instance |
| `app/.../screens/chat/dialogs/InviteDialog.kt` | Displays the full invite host + path prefix (dynamic) |
| `app/.../composables/screens/services/DiscoverView.kt` | Pins Discover to the official invites domain (it is an official-instance service) |
| `app/.../screens/settings/NotificationsSettingsScreen.kt` | Push toggle is UnifiedPush-aware (reflects/controls the actual transport); adds the "Background connection" toggle for background-socket push |
| `app/.../api/StoatAPI.kt` | `loginAs` starts the background-socket service when opted in (all login paths) |
| `app/.../c2dm/ChannelRegistrator.kt` | Adds a low-importance notification channel for the socket service's persistent notification |
| `app/.../StoatApplication.kt` | Hydrates persisted endpoints on startup (before any network use); registers `AppVisibility` foreground tracker |
| `app/.../activities/MainActivity.kt` | Registers the `login/selfhosted` route; skips first-party health/geo checks on custom instances |
| `app/.../screens/login/LoginGreetingScreen.kt` | Adds the "Use a self-hosted server" link |
| `app/.../di/ViewModelModule.kt` | Registers `SelfHostedServerScreenViewModel` |
| `app/src/main/res/values/strings.xml` | Adds `self_hosted_*` strings (appended at end of file) |
| `app/.../c2dm/HandlerService.kt` | Notification rendering moved to `PushMessageRenderer` (FCM behavior unchanged); `generateLetterBitmap` made internal. Upstream changes to rendering logic must be applied to `PushMessageRenderer.render` instead |
| `app/src/main/AndroidManifest.xml` | Adds the UnifiedPush service declaration; the background-socket `specialUse` foreground service + boot receiver + their permissions |
| `gradle/libs.versions.toml` + `app/build.gradle.kts` | Adds the `org.unifiedpush.android:connector` dependency |

## Downloading builds

Every successful build publishes a GitHub release with a dated tag
(`v<version>-<date>-<commit>`; the 10 newest are kept), so the newest build is always at:

```
https://github.com/gubsy420/for-android/releases/latest/download/app-debug.apk
```

For automatic updates on-device, add `https://github.com/gubsy420/for-android` as an app
source in [Obtainium](https://github.com/ImranR98/Obtainium) — each build's dated tag reads
as a new version. Builds are debug builds signed with the committed `.github/debug.keystore`
via the debug `signingConfig` in `app/build.gradle.kts` (standard Android debug passwords),
so every build shares one signature and installs as an in-place update. The `versionCode`
increments per CI build (`GITHUB_RUN_NUMBER`) so updates are seen as newer versions.
Note: because the keystore is public, treat these builds as personal-use; anyone can produce
an APK with the same signature.

## Upstream sync automation

`.github/workflows/sync-upstream.yml` runs daily (or manually from the Actions tab):

- If upstream `dev` has new commits, they are merged into this fork's `dev`.
- Clean merge → pushed automatically, and `build-fork.yml` then produces a fresh APK.
- Merge conflict → a second job hands the conflicted merge to Claude Code (authenticated
  via the `CLAUDE_CODE_OAUTH_TOKEN` repo secret), which resolves it per this document. The
  merge is only pushed if no conflict markers remain **and** the merged code compiles; an
  issue is then opened on the repo (mentioning @gubsy420) summarizing what conflicted and
  how it was resolved.
- If automated resolution fails for any reason (missing token, unresolvable conflict,
  compile failure), the workflow falls back to pushing an `upstream-sync` branch and opening
  a PR against `dev` for manual resolution — the same behavior as before automation.

## Known limitations

- Invite links generated in-app follow the configured instance: `https://<web-app>/invite/<code>`
  on a self-hosted server (matching the web client), `stt.gg/<code>` on the official server.
  **Deep-link caveat:** the Android manifest's invite intent filters only register the known
  official hosts (`stoat.chat`, `stt.gg`, `app.revolt.chat`, `rvlt.gg`), so tapping a
  self-hosted invite link in an external app opens it in a browser rather than the app — the
  instance domain isn't known at build time, so it can't be added to a static intake filter.
  Pasting the code into Add Server, or opening the link from within the app, works.
- Terms/privacy/support/changelog links always point at official Stoat pages.
- Push notifications: three transports are supported, in order of "Google-freeness":
  1. **FCM** — requires a real `google-services.json` at build time and a backend
     configured for the same Firebase project. Used by default when no alternative is set up.
  2. **UnifiedPush** (Google-free) — install a distributor app (e.g. ntfy) and run a backend
     patched for aes128gcm web push (see [selfhost/README.md](selfhost/README.md)). With a
     distributor installed, UnifiedPush takes precedence over FCM (registered on app start
     after login). Non-message pushes (friend requests, calls) render as plain notifications.
  3. **Background connection** (Google-free, no distributor, no backend push) — enable
     *Settings → Notifications → Background connection*. The app keeps its realtime websocket
     alive in a foreground service and renders notifications directly from message frames.
     Because the client evaluates notification settings and mentions (direct/role/mass)
     on-device, this needs no pushd, no web-push encryption, and no MongoDB replica set — it
     sidesteps the entire server-side push pipeline. Trade-offs: a mandatory persistent
     (minimal, low-priority) notification, and higher battery use than a shared distributor;
     on aggressive OEMs (e.g. Samsung) exempt the app from battery optimization for
     reliability. While enabled, UnifiedPush registration is skipped (the socket keeps the
     account permanently online, so distributor/pushd delivery would be redundant). Survives
     reboot via a boot receiver. Only surfaces notifications while the app is backgrounded.
- Registration CAPTCHA uses the key advertised by the official client config; self-hosted
  instances with hCaptcha enabled may not work for in-app registration.
- The "new login experience" (`login2`, debug-only beta) does not expose the self-hosted
  entry point; the standard login flow does.
