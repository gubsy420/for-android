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
| `.github/workflows/sync-upstream.yml` | Daily upstream sync automation |
| `.github/workflows/build-fork.yml` | Debug APK builds |

**Modified upstream files** (kept as small as possible — these are the only places a sync
conflict can involve fork code):

| File | Change |
|---|---|
| `core/model/.../data/Constants.kt` | `STOAT_BASE`, `STOAT_WEBSOCKET`, `STOAT_FILES`, `STOAT_PROXY`, `STOAT_WEB_APP` changed from `const val` to getters delegating to `EndpointConfig` |
| `app/.../StoatApplication.kt` | Hydrates persisted endpoints on startup (before any network use) |
| `app/.../activities/MainActivity.kt` | Registers the `login/selfhosted` route; skips first-party health/geo checks on custom instances |
| `app/.../screens/login/LoginGreetingScreen.kt` | Adds the "Use a self-hosted server" link |
| `app/.../di/ViewModelModule.kt` | Registers `SelfHostedServerScreenViewModel` |
| `app/src/main/res/values/strings.xml` | Adds `self_hosted_*` strings (appended at end of file) |

## Upstream sync automation

`.github/workflows/sync-upstream.yml` runs daily (or manually from the Actions tab):

- If upstream `dev` has new commits, they are merged into this fork's `dev`.
- Clean merge → pushed automatically, and `build-fork.yml` then produces a fresh APK.
- Merge conflict → the workflow pushes an `upstream-sync` branch and opens a PR against
  `dev`. Resolve the conflict manually (the table above tells you exactly which fork changes
  must be retained), then merge the PR.

## Known limitations

- Invite links generated in-app still use the official `stt.gg` domain; links to a
  self-hosted *web app* are recognized, `stt.gg`-style short links of other instances are not.
- Terms/privacy/support/changelog links always point at official Stoat pages.
- Push notifications require the instance to support the same FCM pipeline as the official
  server, and a real `google-services.json` at build time.
- Registration CAPTCHA uses the key advertised by the official client config; self-hosted
  instances with hCaptcha enabled may not work for in-app registration.
- The "new login experience" (`login2`, debug-only beta) does not expose the self-hosted
  entry point; the standard login flow does.
