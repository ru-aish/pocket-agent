# Pocket Agent voice assistant (Android)

## Product contract

Pocket Agent remains the assistant and subagent host. Android's selected digital-assistant
gesture activates a microphone-type Android foreground service with an ongoing
notification, without leaving an overlay on screen. Settings choose one of three STT modes:
**Hybrid**, **Android default only**, or **Deepgram only**. Hybrid preserves the parallel path:
Deepgram receives live PCM from the first sample and, on Android 13+, the same PCM is injected into
an available Android system recognizer without opening a second microphone. Its switch threshold is
configurable from Settings and defaults to three seconds. Flux word timestamps are the preferred
spoken-duration measurement; Android wins below the threshold when available and Deepgram wins at
or above it. **Android default only** keeps the normal system `SpeechRecognizer` as the
transcript source for ordinary turns, matching the standalone Android test path, and keeps hybrid
arbitration disabled. While Pocket Agent is thinking or speaking, Flux temporarily owns the
microphone as a full-duplex interruption bridge; if the user barges in, that stream finishes the
interrupted utterance before Android recognition resumes. **Deepgram only** disables Android
recognition and threshold logic. Deepgram TTS remains common to all three modes.
After the wake-word prefix is removed, a nonempty selected transcript first enters the optional [local command route](QUICK_COMMANDS.md). Supported requests execute through native tools and their real result is recorded in chat without model inference. Other requests are submitted as **one normal main-agent user message**; wake-word-only turns are ignored. Only
text deltas produced in that particular main response are sent to Deepgram streaming
TTS. Subsequent Pi9 worker completion messages and ordinary text messages remain
visible in chat without speech unless the model explicitly calls `speak_to_user`. The listening service ends after three seconds of idle silence (30 seconds while waiting for a local clarification), or immediately via
the notification **Stop listening** action. Neither ending the voice session nor switching
to another app cancels Pi workers. Stop response, Cancel worker and Stop all remain
distinct actions.

This is not Deepgram's Conversational Agent API. No Deepgram model makes decisions,
dispatches tools or starts subagents.

## Reference findings

Verified against private `ru-aish/fe.voice.ind` (read-only), principally
`src/lib/deepgram-agent-config.ts`,
`src/app/deepgram-agent-test/deepgram-agent-panel.tsx`, and
`src/app/demo/components/DemoVoiceSession.tsx`.
The reference uses 16 kHz PCM16 input, 24 kHz PCM16 output, Aura voice synthesis,
and explicit first-response latency event timing. Its demonstration **does** use
Deepgram's Agent SDK and has a different orchestration path; those SDK calls were
not copied into this Android implementation.

Pocket Agent instead uses directly documented Deepgram APIs:

- STT: `wss://api.deepgram.com/v2/listen` using Flux `flux-general-en`, raw
  16 kHz signed PCM16 mono with 80 ms microphone packets (the Deepgram-recommended
  chunk size). Deepgram receives every utterance from the beginning even while the
  Android system recognizer is eligible for a short-turn result. On Android 13+ with
  a compatible recognition service, that same PCM16 stream is supplied through
  `RecognizerIntent.EXTRA_AUDIO_SOURCE`; the system recognizer never owns the microphone.
  The deterministic arbiter prefers the first-to-last Flux word-timestamp span for
  short/long classification (3000 ms by default) rather than callback arrival time or the full
  audio window. This excludes endpointing silence while preserving pauses between recognized
  words. Android's transcript wins below the configured threshold when available; at or above it,
  Deepgram's transcript wins when available. Android begin/end-of-speech timing is only a fallback
  if Flux omits complete word timestamps. Duplicate finals are suppressed. On Android 13+, Pocket Agent
  also requests quality formatting and recognition biasing; when Contacts permission is granted,
  up to 128 contact display names are supplied as explicit bias strings to improve proper-name
  recognition. Flux provisional updates do not initiate model inference.
- TTS: `wss://api.deepgram.com/v1/speak` with Aura-2 Thalia,
  24 kHz signed PCM16 mono. The outgoing Pi response is cut at punctuation
  or a long whitespace boundary, preserving incomplete word fragments.
  One `Flush` is sent per response and Android `AudioTrack` plays received PCM
  incrementally without writing an audio file.
- Only one STT and one TTS connection are opened per **active notification voice session**.
  The microphone stays acquired through TTS playback for interruption and follow-ups.
  It is not a permanent always-on listener and closes on idle silence or explicit Stop.
  There is no wake-word process, greeting request, separate LLM, or polling loop.
- A separate Android Keystore encrypted key is configured in Settings. It is not
  copied from the reference repository and never passed into Pi transcripts.
  The microphone permission must be granted on the device.

## Android activation

1. Install the new APK built by GitHub Actions.
2. Configure a model provider for Pocket Agent and a Deepgram voice API key.
3. Grant **Microphone** from Pocket Agent's settings.
4. Select **Choose default assistant** or set Pocket Agent under Android's
   Settings → Apps → Default apps → Digital assistant app.
5. Configure the power-button long-press to **Digital assistant** if that
   device supports it. Some OEMs reserve this gesture for the power menu.
6. Grant **Allow voice notification** so Android displays the ongoing listening indicator.
7. Long-press the power button to activate voice. No overlay should remain.
   Navigate the phone normally while the microphone notification remains visible.
   Say something; begin speaking while the response is audible to barge in.
8. Stop via **Stop listening** in the notification, **Stop voice** in the main app,
   or allow three seconds of idle silence after the last response (30 seconds while awaiting a local clarification). Workers remain
   active in the existing runtime notification and chat.

The Android manifest registers a protected `VoiceInteractionService` and
`VoiceInteractionSessionService` rather than a global accessibility shortcut.
The voice service itself is lightweight. No platform bypass, root or key interception
is needed. Default-assistant support and key mapping depend on OEM firmware.

## Failure/lifecycle boundaries

- Opening without microphone permission or a Deepgram key yields a setup prompt
  and does not open a microphone.
- Malformed streaming recognition packets and provisional turn updates are ignored;
  only a confirmed nonempty `EndOfTurn` sends a prompt.
- Android 14+ permits this microphone-type foreground service to continue capture
  while other apps are visible. Capture is started by an explicit assistant gesture,
  never silently restarted after process death. The ongoing notification has a
  distinct Stop listening action.
- While Pi thinks or speaks, capture continues without reopening AudioRecord.
  Flux `StartOfTurn` interrupts playback immediately: local queued PCM is discarded,
  Deepgram TTS receives `Clear`, and voice-turn-scoped `stop_main` aborts **only** the previous
  main response. The next confirmed `EndOfTurn` enters Pi as a new user message.
  Late response deltas are ignored using a new identifier for each voice turn.
  Android's available acoustic echo canceler and noise suppression are enabled;
  noisy speakers/headsets and OEM audio routing still require device validation.
- Three seconds of idle silence (30 seconds while awaiting a local clarification) stops the voice service when no user utterance or
  main spoken response is active. A long-running response does not unexpectedly
  lose interruption capability midway through playback.
- Notification stop and idle expiry release both WebSockets, microphone, AudioTrack,
  and pending speech; neither cancels Pi workers. Late Pi deltas cannot speak into
  a new voice session.
- The main queue persists in the existing runtime. Main response failure emits
  `voice_end` and no false spoken success. Tool-only turns return to listening
  without waiting for an empty TTS response.
- Network loss, permission revocation, denied audio hardware and overrun are surfaced
  as voice status; no speech provider error is treated as worker task completion.
- The TTS queue bounds pending synthesis commands and bytes; STT WebSocket
  backlog is capped, avoiding indefinite retained audio. A normal speech socket
  closure, transport failure, or rejected synthesis write marks TTS unavailable
  and releases pending playback; a 30-second flush acknowledgment watchdog
  prevents lost `Flushed` events from keeping the microphone session alive forever.
- There is no wake-word or indefinite screen-off listener. An active user-started
  microphone service can continue when the screen turns off, subject to OEM battery
  and microphone policies. The default-assistant gesture remains the entrypoint.
- The assistant UI finishes immediately after starting the voice service;
  switching apps, pressing Back and opening Recents do not stop an active session.
  Ending the voice service never issues `stop_main` or `stop_all`, but **barge-in**
  deliberately issues `stop_main` to prevent continuing the superseded spoken
  response. Worker tasks remain independent.

## Metrics and verification

### Verified real network/model audio round trip (2026-10-15)

On the separate Arch Linux test runner, Deepgram Aura synthesized a 4.96-second
spoken question, which was resampled and streamed to Flux in paced 80 ms PCM frames.
Flux finalized the correct utterance, and Pocket Agent's **real Pi main thread with
an existing Codex-account model connection** answered: **“Two plus two is four.”**
Deepgram Aura streamed 38 PCM response chunks totaling 1.52 seconds of audio.
The resulting response audio was re-transcribed over another live Flux stream as
exactly **“Two plus two is four.”** (normalized text similarity 1.0, non-silent PCM
RMS 2345). The first model text arrived 3202 ms after finalized transcription,
and first response PCM arrived after 3847 ms. Input speech transcription took
8497 ms including the 4.96 seconds of spoken question. These are one-trial values,
not typical latency guarantees. This confirms actual provider and model audio
transport, **not** physical Android microphone/speaker playback. See
[structured evidence](validation/2026-10-15-voice-e2e.json) and
[the spoken response recording](validation/voice-e2e-response-2026-10-15.ogg).

A voice session reports `PocketVoiceLatency` to logcat as:
`first_token_ms=<elapsed since finalized utterance> first_audio_ms=<elapsed>`.
These are application end-to-end intervals rather than Deepgram's Agent SDK's
provider timing values; compare carefully. Values require actual configured
Deepgram credentials and a recorded spoken utterance; no performance number
is assumed from the reference or from synthetic unit tests.

Validation checklist:

1. Runtime unit tests: speech-tagged main reply produces text deltas and a
   final marker; user text and worker completion do not.
2. Android instrumentation: confirmed Flux turn filtering, early speech
   clause segmentation, assistant-service registration.
3. Build and sign on GitHub Actions; install **that artifact** on Fedora Android
   emulator and select the assistant via the system voice-interaction role.
4. Invoke the assistant and verify no overlay remains; navigate to another app
   while the microphone notification remains visible and a voice response plays.
   Interrupt playback with a second utterance, and confirm only the new reply is
   spoken while unrelated Pi workers continue.
5. Test Stop listening, three-second idle shutdown (30 seconds for local clarification), Android microphone revocation,
   repeated assistant activation, and a worker completion after microphone shutdown.
   The worker completion should appear only in chat.
6. Inspect `logcat -s PocketVoiceLatency:I` for actual voice latency,
   and foreground memory/battery with system tools. Never claim live voice
   latency was measured without a Deepgram-connected voice trial.

Known limitations: Deepgram STT/TTS consumes network access and quota. Hybrid Android short-turn
recognition requires Android 13+ and a recognition service that accepts injected PCM; otherwise
Hybrid falls back to Deepgram. Android default only uses the public Android `SpeechRecognizer`
microphone path and requires an available system recognition service. Neither Android path is
Gboard's private advanced voice-typing pipeline, so Gboard's learned/personalized behavior cannot
be guaranteed. The Hybrid switch threshold can be tuned from 0.50 to 8.00 seconds in Settings and
is disabled in Android-only and Deepgram-only modes.
An OEM may not expose the power-button assistant gesture; it may invoke a
different assistant despite the selected role. Android 12+ background service
and microphone restrictions still apply.

### Current verification boundary

A lightweight live Deepgram barge-in probe is available at
`scripts/live_deepgram_barge_in.py`. It synthesizes a short interruption with Aura
while a longer Aura response is streaming, feeds the interruption to Flux in paced
80 ms PCM frames, waits for `StartOfTurn`, clears the response TTS stream, and
requires both immediate local playback cancellation and Deepgram's `Cleared`
acknowledgement. It uses a short-lived credential from the existing voice.ind token
endpoint and does not boot an emulator or persist the credential. The 2026-10-20
run is recorded in `validation/2026-10-20-live-barge-in.json`.

The previous real Pi audio round trip and the live barge-in probe do not prove
physical Android microphone/speaker acoustics. Device-specific echo cancellation,
audio routing, and background behavior still require the signed APK on a real phone.

## Optional agent-initiated announcements

`speak_to_user({message,listen_after?})` is an explicitly invoked Pi tool available
in main and delegated agent sessions (including read-only researcher sessions). It
is never invoked automatically just because a worker finishes. An external
scheduler may trigger a future agent turn that invokes the tool, but this app
currently has no native scheduled-agent trigger (`phone_alarm` sets a clock
alarm only). Speech requests cannot run after the agent process has stopped.

- The tool validates at most 800 characters and returns `status: requested`,
  **not** a claim that Android accepted the request or that audio played. Speech uses Deepgram Aura on Android,
  with a private internal service Intent. It does not use a separate LLM.
- `listen_after` defaults to `true`: an available microphone is opened for
  interruption during speech and for a short follow-up. The microphone session
  closes after **three seconds of silence following playback**. If set to false,
  audio-only announcements do not request microphone permission or recording.
- Speech initiated by an agent is independent of the original voice session;
  workers and normal main input still run while idle microphone sessions have
  closed. An existing interactive session continues on its own. Interruption of
  an announcement does not cancel its worker.
- Competing announcements are queued (maximum eight waiting messages); Android
  can refuse background foreground-service starts or microphone activation.
  The app surfaces a notification when an announcement service launch is denied;
  playback and follow-up listening are not guaranteed after background limits,
  network loss, credential removal, or process termination. Announcement text
  must not include passwords, verification codes or sensitive data the user did
  not explicitly ask to hear aloud.
- Stop voice closes audio capture and playback without cancelling agent work;
  Stop all still ends the agent runtime. No perpetual listening process is added.
