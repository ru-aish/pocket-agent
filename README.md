# Pocket Agent

An Android assistant that delegates phone work to background agents while you keep talking to the main conversation. Pi and the adapted `@pi9/subagent` runtime run inside the app; workers call native Android tools directly. Settings can route speech-to-text three ways: **Hybrid**, **Android default only**, or **Deepgram only**. Hybrid sends the same PCM to Deepgram and, on Android 13+, an available system recognizer in parallel; its configurable switch threshold defaults to 3 seconds, with Android preferred below the threshold and Deepgram at or above it. Android default only uses the phone's normal `SpeechRecognizer` microphone path with no hybrid arbitration or threshold. Deepgram only skips Android recognition entirely. Deepgram continues to provide speech synthesis in every mode.

**Preview:** [v0.1.1-preview.1](https://github.com/ru-aish/pocket-agent/releases/tag/v0.1.1-preview.1) adds direct Codex account sign-in and secure login-cache import. The signed x86_64 APK passed direct Luna delegation with the relay stopped. ARM64 execution remains unverified. See the [account validation record](docs/CODEX_AUTH_VALIDATION.md).

## Install and connect

### Mobile chat UI and private display viewing

The app now packages the same phone-first interface used by the local browser
companion: swipeable chat drawer, short conversation titles, writable previous
chats, a compact composer, and separate subagent threads. Settings includes
provider sign-in/model discovery, system/Groq/Deepgram voice options, phone
permissions, MCP connections and background-work controls. Original advanced
Android voice/account controls remain available.

Open **Activity → Displays** to inspect three on-demand Shizuku slots and watch
an active private screen without controlling it. Preview frames are requested
only while that viewer is open. Private displays hold their own power-group lock
while leased, so the physical display can remain off. Rich editors can use the
explicit keyboard typing path and must be reopened to verify persistence.
See [mobile UI validation and limitations](docs/MOBILE_UI_VALIDATION.md) and
[running the local companion](preview/README.md).

Requires **Android 12 or later**. This is a separate app, `dev.pocket.agent`, and does not replace Pocket MCP.

1. Download a signed APK from a published release or the `pocket-agent-release` artifact of a successful Actions run. `Pocket-Agent-x86_64.apk` targets the emulator; `Pocket-Agent-arm64.apk` targets ARM64 phones. ARM64 execution is still unverified. A universal APK is also built.
2. Open **Settings** and choose a provider. Choose **Codex account** to sign in with your existing ChatGPT account or import your Codex `auth.json`. No API key or endpoint setup is needed. **OpenCode Free** uses OpenCode's fixed external inference service and discovers only currently advertised free models that use the OpenAI Chat Completions route Pocket Agent supports. Pocket Agent currently requires a Console **service-account key** for inference. **Nebius Token Factory** uses its current fixed OpenAI-compatible endpoint and requires a Nebius API key. Its default `zai-org/GLM-5.3-Flash` is vision-capable for screenshot workflows; other discovered models are treated as text-only unless their model ID identifies a vision/VL/omni route. **API key (HTTPS)** remains available for other OpenAI Responses-compatible endpoints. For API-key providers, enter the key and tap **Discover models** before saving to load its catalog. **Test connection** checks discovery and a small backend reply without saving; **Save** runs those checks only when the API key changes and keeps the previous connection if they fail. Model and preference changes with the same key save without connection tests. Choosing a model from the drop-down fills the **main chat model**; save to apply. Manual model ID entry remains available; new or changed API keys must pass discovery and inference checks before saving. `gpt-6-luna` is the emulator-tested Codex default.
3. For UI observation and interaction, enable **Pocket Agent phone tools** under Accessibility settings. The default shell-backed virtual-display path (including use while the physical screen is off, subject to Android/OEM restrictions) also requires starting Shizuku (via its own app), then selecting **Enable Shizuku virtual displays** under Pocket Agent Settings and authorizing access. When you explicitly ask Pocket Agent to inspect, control, open, or present something on the visible device screen, its UI tools can instead target the physical display; this path uses the Accessibility service for observation, actions, and visible-display launches, and requires neither Shizuku nor a virtual-display slot. **Choose phone permissions** lets you grant contacts, calls/phone status, SMS, calendar, and runtime notifications separately. Notification access is needed for notification-reading tools. Direct APIs for SMS, calls and similar actions do **not** require Shizuku or a display.
4. Send a message. **Send** starts the user-visible foreground service, with an ongoing notification. For example: “Delegate checking the current screen to a phone-operator worker, and report what it sees.”

**OpenCode external-auth note:** Pocket Agent does not impersonate the OpenCode client or copy its private identity headers. Create a Console service account with **Inference only** permission and paste its key into Settings. Pocket Agent sends that key as `Authorization: Bearer <key>` only to the fixed `https://opencode.ai/inference/openai/v1` origin. Service-account keys are workspace-bound, so no workspace header is required. The public catalog remains usable for discovering currently advertised free Chat Completions model IDs; provider-specific routes such as SystemOne are excluded.

**Per-task model routing:** The model selected in Settings is always the main assistant's model. It can call `subagent` with `action: "models"` to fetch the current provider's model catalog, with optional `query`, `offset`, and `limit` for large catalogs. Each `spawn` can name any discovered `model` ID from that same provider, or omit it to inherit the main model. Delegated sessions, including resumed workers, retain their selected model; the main conversation remains unchanged. Provider model listing is bounded and authenticated when the provider requires it; OpenCode's public free-model catalog is intentionally anonymous. No secret values are persisted in the catalog. Some listed models may not support tool calling/Responses, and an advertised list does not guarantee access or disclose model prices; provider errors are surfaced rather than silently switching models.

**Long-task test build:** The `0.1.5-long-tasks-preview.1` branch build raises per-generation limits to 200 turns / 800 tool calls and retained context to 2,048 messages / 8 million serialized characters. Existing provider context limits and billing still apply.

**Virtual-display test build:** The `0.1.4-display-preview.1` branch build is synchronized with Pocket MCP's Shizuku startup/timeout handling through `a7c667c`. Android version code 4 installs over older previews. This integrated branch now includes the voice-assistant functionality merged into `main`, alongside Shizuku-backed virtual displays.

**Stop response** stops main's current response. Open a task to inspect, message, steer, or cancel that worker. Closing its view leaves its work running. **Stop all**, also available in the notification, stops the service and all work.

Settings also offers **Scripted model (local validation)**, which uses no cloud inference and is intended for the included fixture tests. It is not a general conversational model. Credentials are encrypted with Android Keystore; replace the saved key explicitly when switching providers. See [model connections](docs/AUTH.md).

**Remote MCP servers:** Open Settings → **Remote MCP servers** to add, edit, enable, disable, test, discover tools from, or delete up to ten Streamable HTTP servers. Server URLs must use HTTPS. Optional authentication supports no auth, a bearer token, or up to eight custom headers; credentials share the Android Keystore encrypted store. OAuth is not available, so servers that require OAuth cannot be connected. MCP tools are available to the main assistant and phone-operator workers, but not the researcher; every agent has the local general shell separately. Connect only servers you trust because their tools receive the arguments the model sends.

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

## Scheduled work and personal instructions

Ask the assistant to save a schedule, a future wake-up, or a notification watch.
Background work uses persistent jobs and dedicated workers on this phone;
Android can delay execution. Open **Activity** to review runs, answer questions,
approve a task's next phone actions, or pause/resume automation. **Stop all** also
pauses future automation. Default unattended direct phone tools are read-only; the general shell is available to every worker as a standing user grant.

Settings exposes **Personal instructions** for preferences. Core operating
instructions are source-owned in `runtime/src/prompts.ts` and are not editable or
displayed in the app. Existing custom prompt text migrates to personal preferences.
See [scheduled and autonomous work](docs/AUTONOMY.md) for tools and limits.

## On-phone terminal

The main assistant and every subagent also have `phone_shell`: a general Shizuku-backed terminal running entirely on the phone. It can execute arbitrary shell code, retain scripts for later tasks, inspect paged command output, and capture private-display images independently of accessibility. Commands have no allowlist or display filtering; agents receive their display context as guidance. Shizuku must be running and authorized. No PC or USB connection is required during use. See [shell setup, script reuse, and lifecycle](docs/SHELL.md).

## How it runs

One app-owned Javet Node engine hosts main and every logical worker. Pi9 supplies the in-process scheduler and retained worker conversations. Direct native tools share a FIFO pool of three on-demand Shizuku virtual displays (fourth UI worker waits without failing), bounded batching, and a mutation ledger. Worker completion reaches main through a persisted event inbox rather than repeated model polling. App-private SQLite stores transcripts and task checkpoints separately from protected credentials.

The local-history implementation under development archives user/assistant text from main and worker checkpoints into append-only history and FTS tables in the app-private `agent-state.db`, committed atomically with the live checkpoint. **Search saved conversations** works without the service or an external memory server; the main agent can also use `history_search` and `history_read` across old threads. Worker searches see main and their own history, not other workers' private transcripts. Screenshots and tool-call payloads are deliberately excluded. This is lexical search, **not Mem0 or on-device semantic embeddings**; it cannot reliably match paraphrases by meaning yet, and it cannot reconstruct older transcripts already deleted before this implementation was installed. Core history persistence, v1 database migration, transaction rollback, search/read tools, and the search UI passed on an Android 14 ARM64 phone using an isolated debug APK; see [the phone validation record](docs/HISTORY_PHONE_VALIDATION.md). Large-history performance, release-build execution, and battery/OEM behavior still need verification.

Android can restrict or terminate the user-started service. Interrupted work is preserved as interrupted; side effects are not automatically replayed. Interactive device-code approval, release-build ARM64 execution, physical-phone battery/OEM behavior, and comparative resource measurements remain outstanding. Build success is not full runtime validation.

## Development

For the latest test build from `main`, open the [**Development prerelease**](https://github.com/ru-aish/pocket-agent/releases/tag/development) and expand **Assets**. Download **`Pocket-Agent-development-arm64.apk`** for an ARM64 phone, `Pocket-Agent-development-x86_64.apk` for an emulator, or `Pocket-Agent-development-universal.apk` if unsure. The [Scheduled development APK workflow](https://github.com/ru-aish/pocket-agent/actions/workflows/development-apk.yml) checks every three hours and only builds when `main` differs from the last successfully published development build. See [development build downloads and schedule](docs/DEVELOPMENT_APK.md#downloads-and-schedule) for details.

**Compile and sign APKs only in GitHub Actions.** The workflow builds release/test APKs, runs runtime tests and lint, and packages hashes and provenance. Emulator/device execution is a separate local step; use an explicit emulator serial, as described in the validation documentation.

- [Voice assistant: Deepgram, Android role, lifecycle, and latency](docs/VOICE.md)
- [Runtime architecture, upstream pins, and limits](docs/RUNTIME.md)
- [Android host, lifecycle, permissions, and instrumentation](docs/ANDROID_HOST.md)
- [Development APK: USB/localhost MCP, local transcripts and optional ARM64 ngrok tunnel](docs/DEVELOPMENT_APK.md)
- [Authentication and relay setup](docs/AUTH.md)
- [Validation evidence and remaining limits](docs/VALIDATION.md)
- [Original implementation requirements](docs/IMPLEMENTATION_HANDOFF.md)

### ChatGPT desktop on a VPS

Use the signed-in native ChatGPT desktop over Tailscale as an inference provider, with PocketAgent-owned tools and workers. See [setup and provider behavior](docs/CHATGPT_DESKTOP.md).
