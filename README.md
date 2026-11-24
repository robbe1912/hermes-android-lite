# hermes-android-lite

A lean Android client for a self-hosted [Hermes Agent](https://hermes-agent.nousresearch.com) gateway.

Unofficial community project. Not affiliated with Nous Research, Tailscale or Google.

## What it does

- Chat with your Hermes agent: channels, threads, streaming replies.
- Answer the questions and the approval requests of the agent in the thread.
- Open each cron run as a thread and reply in it. Read the report of a script run.
- Get a push notification when a cron run ends.
- Reach your gateway away from home with Tailscale inside the app. You need no VPN app.
- Speak with the agent: a voice button for the input, and a setting that reads answers aloud.
- Four screens: threads, thread, cron, settings. No admin console.

## How it reaches your gateway

- **LAN:** the app talks to the gateway API at `http://<ip>:9119`.
- **Tailscale inside the app (optional):** the app contains the Tailscale client library. It needs no VPN permission. Sign in once in the app. The gateway needs a Tailscale subnet router on your LAN, or a Tailscale address of its own. The app builds the library without the log upload feature of Tailscale.
- **Route "Auto":** the app uses the LAN at home. Away from home it uses Tailscale.
- **Push (optional):** a small relay on your server sends a data message without content through your own Firebase project.

The app writes one small file into the Hermes home when you turn push on. The relay is the only software on the server.

## Set up the app

1. Install the APK from the releases page.
2. Enter the address of the gateway, for example `<IP>:9119`.
3. Sign in with the password of the dashboard, or with the dashboard token.
4. Tap **Save and test**. The app signs in and shows the threads.
5. For the way from outside: tap **Sign in to Tailscale** once. Open the link and approve the device in your tailnet.

## Push

Push needs a Firebase project of your own.

1. In the [Firebase console](https://console.firebase.google.com): make a project. Add an Android app with the package name `com.robbe1912.hermeslite`. Download `google-services.json`.
2. In the project: IAM and administration, Service accounts. Make a service account with the role **Firebase Cloud Messaging API Admin**. Make a JSON key for it.
3. On the gateway server, put the key at `/etc/hermes-lite-push/service-account.json` with the owner `root:hermes` and the mode `640`. Then run `sudo sh relay/install.sh` and `sudo systemctl enable --now hermes-lite-push`.
4. In the app: Settings, Push, **Choose google-services.json**. The file stays on the device. The app reads four values from it and writes its push token into `<Hermes home>/hermes-lite-push/`.

The relay reads the cron jobs of the gateway every 5 seconds. When a run ends, it sends one wake ping to each registered device. The notification opens the run. A tap on it needs a route to the gateway.

`HERMES_LITE_DRY_RUN=1` makes the relay log each wake ping instead of sending it. All settings are variables with the prefix `HERMES_LITE_`. See the top of `relay/hermes_lite_relay.py`.

## Privacy

- The app stores the password or the token with a key in the Android Keystore.
- The app has no analytics and no ads. It talks to your gateway, to Tailscale, and to Firebase when push is on.
- The Tailscale library sends no debug logs to Tailscale. It is built without its log upload feature.
- The voice button uses the speech recognizer of the device. The recognizer of Google sends the audio to Google. The read aloud setting uses the voice engine of the device.
- The push message carries the name and the status of a cron run. It carries no content of the run.

## Hard rule for contributors

**Never commit a secret to this repository. This is a public repository.**

- No auth keys, passwords, tokens, API keys, private keys or signing keys.
- No `google-services.json`, no service account files, no keystores.
- Keep every real file of that kind **outside** the repository directory.
- Documentation uses placeholders only, for example `<AUTH_KEY>`.

Three guards enforce the rule:

| Guard | What it does |
|---|---|
| `.gitignore` | ignores secret file names |
| `.githooks` | stops a commit or a push that holds a secret pattern |
| CI | scans the full history on each push |

## Build from source

You need JDK 17 or newer, Go 1.27.4 or newer, and the Android SDK with platform 37.1, build-tools 36.0.0 and NDK 27.

```sh
git clone https://github.com/robbe1912/hermes-android-lite
cd hermes-android-lite
git config core.hooksPath .githooks   # enable the secret guards
(cd tunnel && go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind)
./gradlew :app:assembleDebug
```

The first build makes the Tailscale tunnel library from source with gomobile. It takes about two minutes. Open the project in Android Studio and run it.

The tests:

```sh
(cd tunnel && go test ./...)
python3 -m unittest discover -s relay -p "test_*.py"
./gradlew :app:testDebugUnitTest
```

## Releases

A tag that starts with `v` makes CI build a signed APK for arm64 and publish it under Releases with a `.sha256` file. The other tests of CI must pass first.

The signing key is not in the repository. CI reads it from these repository secrets: `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. A local release build reads the same values from the variables `HERMES_LITE_KEYSTORE`, `HERMES_LITE_KEYSTORE_PASSWORD`, `HERMES_LITE_KEY_ALIAS`, `HERMES_LITE_KEY_PASSWORD`.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). The app shows the notices under Settings.
