# hermes-android-lite

A lean Android client for a self-hosted [Hermes Agent](https://hermes-agent.nousresearch.com) gateway.

Unofficial community project. Not affiliated with Nous Research or with Tailscale.

> **Status:** work in progress (started 2026-11-24). There is no release yet.

## Goal

- Chat with your Hermes agent: channels, threads, streaming replies.
- Open each cron run as a thread and reply in it.
- Get an instant push notification when a cron job ends.
- Reach your gateway away from home without a separate VPN app.
- Stay lean: four screens, no admin console.

## How it reaches your gateway

- **Direct:** the app talks to the gateway API on your LAN (`http://<ip>:9119`).
- **Tailnet inside the app (optional):** the app contains the Tailscale client library. The app needs no VPN permission, and you need no VPN app.
- **Push (optional):** your server sends an empty wake ping through your own Firebase project.

The app changes nothing on the gateway and adds no server software, except one small relay script for push.

## Hard rule for contributors

**Never commit a secret to this repository.** This is a public repository.

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

## Develop

Prerequisites: Android Studio (with SDK and NDK), Go 1.26 or newer.

```sh
git clone https://github.com/robbe1912/hermes-android-lite
cd hermes-android-lite
git config core.hooksPath .githooks   # enable the secret guards
go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest
```

Open the project in Android Studio and run it. The first build makes the tunnel library from source.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
