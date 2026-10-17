# Pocket Agent

An Android assistant that delegates phone work to background agents while you keep talking to the main conversation. Pi and the adapted `@pi9/subagent` runtime run inside the app; workers call native Android tools directly. Settings can route speech-to-text three ways: **Hybrid**, **Android default only**, or **Deepgram only**. Hybrid sends the same PCM to Deepgram and, on Android 13+, an available system recognizer in parallel; its configurable switch threshold defaults to 3 seconds, with Android preferred below the threshold and Deepgram at or above it. Android default only uses the phone's normal `SpeechRecognizer` microphone path with no hybrid arbitration or threshold. Deepgram only skips Android recognition entirely. Deepgram continues to provide speech synthesis in every mode.

**Preview:** [v0.1.1-preview.1](https://github.com/ru-aish/pocket-agent/releases/tag/v0.1.1-preview.1) adds direct Codex account sign-in and secure login-cache import. The signed x86_64 APK passed direct Luna delegation with the relay stopped. ARM64 execution remains unverified. See the [account validation record](docs/CODEX_AUTH_VALIDATION.md).

## Install and connect

Requires **Android 12 or later**. This is a separate app, `dev.pocket.agent`, and does not replace Pocket MCP.

1. Download a signed APK from a published release or the `pocket-agent-release` artifact of a successful Actions run. `Pocket-Agent-x86_64.apk` targets the emulator; `Pocket-Agent-arm64.apk` targets ARM64 phones. ARM64 execution is still unverified. A universal APK is also built.
2. Open **Settings** and choose a provider. Choose **Codex account** to sign in with your existing ChatGPT account or import your Codex `auth.json`. No API key or endpoint setup is needed. **OpenCode Free** uses OpenCode's fixed external inference service and discovers only currently advertised free models that use the OpenAI Chat Completions route Pocket Agent supports. Pocket Agent currently requires a Console **service-account key** for inference. **Nebius Token Factory** uses its current fixed OpenAI-compatible endpoint and requires a Nebius API key. Its default `zai-org/GLM-5.3-Flash` is vision-capable for screenshot workflows; other discovered models are treated as text-only unless their model ID identifies a vision/VL/omni route. **API key (HTTPS)** remains available for other OpenAI Responses-compatible endpoints. Save the connection, reopen Settings, and tap **Discover models** to load the provider's model catalog. Choosing a model from the drop-down fills the **main chat model**; save to apply. Manual model ID entry remains available if discovery is unsupported. `gpt-6-luna` is the emulator-tested Codex default.
3. For UI observation and interaction, enable **Pocket Agent phone tools** under Accessibility settings, start Shizuku (via its own app), then select **Enable Shizuku virtual displays** under Pocket Agent Settings and authorize access. UI workers run on shell-backed virtual displays instead of the physical screen, including when that screen is off (subject to Android/OEM restrictions). **Choose phone permissions** lets you grant contacts, calls/phone status, SMS, calendar, and runtime notifications separately. Notification access is needed for notification-reading tools. Direct APIs for SMS, calls and similar actions do **not** require Shizuku or a display.
4. Send a message. **Send** starts the user-visible foreground service, with an ongoing notification. For example: “Delegate checking the current screen to a phone-operator worker, and report what it sees.”

**OpenCode external-auth note:** Pocket Agent does not impersonate the OpenCode client or copy its private identity headers. Create a Console service account with **Inference only** permission and paste its key into Settings. Pocket Agent sends that key as `Authorization: Bearer <key>` only to the fixed `https://opencode.ai/inference/openai/v1` origin. Service-account keys are workspace-bound, so no workspace header is required. The public catalog remains usable for discovering currently advertised free Chat Completions model IDs; provider-specific routes such as SystemOne are excluded.

**Per-task model routing:** The model selected in Settings is always the main assistant's model. It can call `subagent` with `action: "models"` to fetch the current provider's model catalog, with optional `query`, `offset`, and `limit` for large catalogs. Each `spawn` can name any discovered `model` ID from that same provider, or omit it to inherit the main model. Delegated sessions, including resumed workers, retain their selected model; the main conversation remains unchanged. Provider model listing is bounded and authenticated when the provider requires it; OpenCode's public free-model catalog is intentionally anonymous. No secret values are persisted in the catalog. Some listed models may not support tool calling/Responses, and an advertised list does not guarantee access or disclose model prices; provider errors are surfaced rather than silently switching models.

**Long-task test build:** The `0.1.5-long-tasks-preview.1` branch build raises per-generation limits to 200 turns / 800 tool calls and retained context to 2,048 messages / 8 million serialized characters. Existing provider context limits and billing still apply.

**Virtual-display test build:** The `0.1.4-display-preview.1` branch build is synchronized with Pocket MCP's Shizuku startup/timeout handling through `a7c667c`. Android version code 4 installs over older previews. This integrated branch now includes the voice-assistant functionality merged into `main`, alongside Shizuku-backed virtual displays.

**Stop response** stops main's current response. Open a task to inspect, message, steer, or cancel that worker. Closing its view leaves its work running. **Stop all**, also available in the notification, stops the service and all work.

Settings also offers **Scripted model (local validation)**, which uses no cloud inference and is intended for the included fixture tests. It is not a general conversational model. Credentials are encrypted with Android Keystore; replace the saved key explicitly when switching providers. See [model connections](docs/AUTH.md).

## Codex account testing on an emulator

Use an existing local Codex login directly in the emulator:

```sh
python3 scripts/emulator_test.py --serial emulator-5580 --artifacts artifacts/BUILD --fresh --codex-account
```

This imports credentials privately into Android's encrypted store for the test,
uses direct Luna inference, deletes the transient file, and clears emulator
credentials afterward. It does not change the PC's Codex cache. Phone tools and
all agent scheduling execute locally in the APK. See [AUTH.md](docs/AUTH.md) for
device sign-in, secure import and the optional legacy development relay.

## How it runs

One app-owned Javet Node engine hosts main and every logical worker. Pi9 supplies the in-process scheduler and retained worker conversations. Direct native tools share a FIFO pool of three on-demand Shizuku virtual displays (fourth UI worker waits without failing), bounded batching, and a mutation ledger. Worker completion reaches main through a persisted event inbox rather than repeated model polling. App-private SQLite stores transcripts and task checkpoints separately from protected credentials.

Android can restrict or terminate the user-started service. Interrupted work is preserved as interrupted; side effects are not automatically replayed. Interactive device-code approval, ARM64 execution, physical-phone battery/OEM behavior, and comparative resource measurements remain outstanding. Build success is not full runtime validation.

## Development

**Compile and sign APKs only in GitHub Actions.** The workflow builds release/test APKs, runs runtime tests and lint, and packages hashes and provenance. Emulator/device execution is a separate local step; use an explicit emulator serial, as described in the validation documentation.

- [Voice assistant: Deepgram, Android role, lifecycle, and latency](docs/VOICE.md)
- [Runtime architecture, upstream pins, and limits](docs/RUNTIME.md)
- [Android host, lifecycle, permissions, and instrumentation](docs/ANDROID_HOST.md)
- [Development APK: USB/localhost MCP, local transcripts and optional ARM64 ngrok tunnel](docs/DEVELOPMENT_APK.md)
- [Authentication and relay setup](docs/AUTH.md)
- [Validation evidence and remaining limits](docs/VALIDATION.md)
- [Original implementation requirements](docs/IMPLEMENTATION_HANDOFF.md)
