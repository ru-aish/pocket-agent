# Pocket Agent local companion

Use Node.js 22.19.0 or newer. Install the repository's pinned runtime dependencies once, then run:

```sh
cd runtime && npm ci && cd ..
node preview/server.mjs
```

Open http://localhost:3000/. The preview remains phone-sized on desktop and fills
the screen on mobile. HTML, CSS, and JavaScript edits reload the browser through
server-sent events. The server binds only to the local machine.

The composer provider picker opens AI settings. Codex supports device-code login,
auth.json import, cancellation, sign-out, model discovery, and refresh through the
repo's Codex adapter. Nebius and OpenCode use their own keys and fixed endpoints.
Messages go through the existing runtime provider adapters; no fake assistant
reply is generated. The local companion has text inference, not Android tools.

Voice input is separate from AI setup and fills a draft for review. System speech
needs no cloud key and requires browser speech-recognition support. Groq Whisper
uses whisper-large-v3-turbo with the multipart protocol from the local
SinCosMay/phone-control checkout. Deepgram has a separate key. Settings show only
the selected engine's key form. Read aloud uses system speech, or Deepgram when
that engine is selected. Android keeps its own live voice/wake-word services.

Browser preferences and conversations use local storage. Provider/account/voice
credentials never do: the server keeps an AES-GCM encrypted vault and a mode-0600
key in `~/.local/state/pocket-agent/live-ui/`. These are local-computer credentials,
separate from Android Keystore. Do not publish or tunnel this local server.

Schedules, subagents, and virtual displays still run inside Android. Browser
Activity explains its unavailable phone state. The native Activity screen reads
real schedules and run history, hides technical output under View details, and
shows worker/display status in the chat. No phone data is fabricated in the browser.

Android packages `index.html`, `style.css`, the shared chat/drawer modules and
`native-app.js` as offline assets. `MainActivity.java` binds them to native stores,
permissions and services through `PocketWebUi`. The browser companion uses
`app.js`; its credentials and messages remain separate from the phone.

Run `npm test`, `npm run test:ui` and `npm run build` from `runtime/`. The UI tests
exercise the encrypted provider service, draft-key validation, speech protocols,
and the fresh-server module graph. For isolated browser QA, set
`POCKET_PREVIEW_PORT` and `POCKET_PREVIEW_STATE_DIR` before starting the server.
See [mobile UI validation](../docs/MOBILE_UI_VALIDATION.md) for Android, browser,
Shizuku and real-device coverage and remaining limitations.

Protocol references: [Codex authentication](https://learn.chatgpt.com/docs/auth),
[Groq speech](https://console.groq.com/docs/speech-to-text),
[Deepgram transcription](https://developers.deepgram.com/docs/pre-recorded-audio),
[Deepgram speech](https://developers.deepgram.com/reference/text-to-speech/speak-request).
