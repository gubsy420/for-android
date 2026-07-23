# Self-hosted server: enabling Google-free push (UnifiedPush)

The fork's Android app supports [UnifiedPush](https://unifiedpush.org) as an alternative to
FCM, so notifications work without Google services. For it to function, your server's push
daemon must encrypt Web Push payloads with the modern `aes128gcm` encoding instead of the
legacy `aesgcm` draft it currently uses ([pushd-aes128gcm.patch](pushd-aes128gcm.patch) —
a one-line change that is also fully compatible with browser clients).

## 1. Build the patched pushd image

Run the **"Build patched pushd image"** workflow in this repo's Actions tab, setting
`backend_ref` to the backend version your compose file pins (e.g. `v0.13.8`). It builds the
Stoat backend with the patch applied and publishes:

```
ghcr.io/gubsy420/pushd:v0.13.8-aes128gcm
```

Note: GHCR packages are private by default. After the first push, make the `pushd` package
public (GitHub → your profile → Packages → pushd → Package settings → Change visibility) or
configure `docker login ghcr.io` on your server.

## 2. Point your compose file at it

In the `stoatchat/self-hosted` `compose.yml`:

```yaml
  pushd:
    image: ghcr.io/gubsy420/pushd:v0.13.8-aes128gcm
```

Then `docker compose pull pushd && docker compose up -d pushd`.

Ensure VAPID keys are configured for your instance (`Revolt.toml` / `secrets.env` —
the self-hosted setup normally generates these; pushd panics at startup if missing).

## 3. On your phone

1. Install a UnifiedPush distributor, e.g. [ntfy](https://ntfy.sh) from F-Droid or Play
   (point it at ntfy.sh or your own ntfy server).
2. Install the fork's APK, connect to your server, and log in.
3. On next app start after login, the app detects the distributor and registers
   automatically — no FCM or `google-services.json` needed.
4. Grant the notification permission (Settings → Notifications inside the app, or the
   system prompt).

To verify: check `docker compose logs pushd` for web push sends, and that messages sent
while the app is closed produce notifications.

## Keeping the patched image up to date

When you bump your backend version in compose, re-run the workflow with the new
`backend_ref` and update the image tag. If upstream ever merges the aes128gcm change
(worth a PR — it modernizes browser push too), the stock image works and this override
can be removed.
