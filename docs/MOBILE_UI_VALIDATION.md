# Mobile UI and background display validation

This change is based on upstream main `26341c4`, retaining its exact-alarm
scheduler and draft-provider validation. The UI packages local HTML/CSS/JS in
Android; it does not depend on a laptop server for phone operation.

## Coverage

- Runtime: `cd runtime && npm test` — 195 tests passed, including writable main
  history, worker ownership, display lifecycle, safe Codex failures and stream
  deadlines/cancellation.
- Browser service: `npm run test:ui` — 13 tests passed, including encrypted
  credentials, rejected draft preservation, speech protocols and a fresh HTTP
  server serving the entire imported chat-module graph. CI runs these tests.
- Repository scripts: `python3 -m unittest discover -s scripts -p 'test_*.py'`
  — 19 tests passed with Java 17 available.
- Build: isolated debug/development APKs, instrumentation compilation and
  `:app:lintDebug` and `:app:minifyReleaseWithR8` pass. The R8 mapping retains
  the offline bridge's `request` JavaScript entrypoint. Existing dependency/deprecation/private-API warnings
  remain; lint errors are not suppressed.
- Browser: 84 fresh screenshots at 390 CSS pixels with touch/DPR 3 and 1440
  desktop pixels, both themes, top/end scroll positions, home/draft/drawer,
  settings routes and Activity tabs. No horizontal overflow. The capture tool
  resets emulation on navigation; captures were regenerated after correcting it.
- Android emulator: shared UI route matrix, keyboard dismissal/draft sizing,
  swipe gestures, live thread scroll/draft preservation, earlier chat resumption,
  history persistence and native tool contracts are exercised by
  `NativeUiDeviceTest`, `ConversationHistoryDeviceTest`, `NativeApiDeviceTest`
  and `PhoneTargetDeviceTest`. The first three suites pass all 25 tests.
  Notification access and the fixture app must be
  provisioned on the disposable emulator before relevant native tests.
  An additional full `PhoneTargetDeviceTest` run was interrupted after seven
  tests when the emulator exited following graphics-context errors. It is not
  counted as a passing full display-suite run; rerun it on a stable emulator
  or device before treating that coverage as complete.
- Earlier ARM64 Android 13 phone validation: the app agent edited an existing
  Keep note through real display-targeted keyboard events, saved and reopened
  it; all 15 expected entries persisted. A private display power lock restored
  navigation while the built-in display remained OFF. The lease released its
  lock afterward. This is functional evidence for that device, not an OEM-wide
  guarantee or a production-release signing test.

## Behavior and limits

Activity → Displays has three real, on-demand slots. Opening an active slot
polls a bounded JPEG preview once per second. It neither allocates another
display nor injects input. Closing/hiding the viewer stops frame requests.
Display 0 and unowned displays are rejected. Frames are not stored in history.

Shizuku's private display holds a screen wake lock scoped to its own power group
while leased and releases it on cleanup or Binder death. Group 0 is rejected.
Android 14+ candidates also require independent focus and no-top-focus-stealing
flags, following the protection proposed in upstream PR #47. No compatibility
fallback removes these protections. Preview/text Binder transaction IDs 11/12
avoid IDs 9/10 used by the alternative surface preview in upstream PR #48.
Those PRs overlap this work and still need integration review if merged first.

`phone_act` defaults to accessibility typing. Rich editors such as Keep may
display replacement text without persisting it. The explicit `input_method:
"keyboard"` path requires an observed, focused editable target on an owned
private display and confirms selection before replacement. It preflights up to
2000 characters against Android's keymap; unsupported characters fail before
typing. There is no automatic replay after partial/uncertain injection. Reopen
the edited item to establish task completion; accepted input is not proof of save.

Codex HTTP/auth/quota/model/context and transport failures have distinct safe
messages. Streams have a ten-minute overall request limit and five-minute idle
limit. Cancellation and the existing bounded pre-output retry policy remain;
streamed tool actions are not replayed. These bounds do not guarantee provider
availability or bypass account quotas.

The native app retains Android Keystore storage and original advanced voice,
wake-word, permission, MCP and automation controls. Browser credentials live in
a separate local encrypted vault. No user credentials, notes, transcripts or
debug captures are included in this contribution. Real cloud Groq/Deepgram
audio, every OAuth interaction, release signing and all OEM/Android combinations
have not been revalidated by this PR. The standalone browser cannot execute
Android schedules or phone tools.

### Menu responsiveness and composer controls

The WebView no longer rebuilds the detached native transcript on runtime updates.
Snapshot/status reads and snapshot serialization run on a dedicated worker, and
bursty runtime notifications coalesce into a refresh with a trailing read. Drawer
history loads after the opening transition; search requests are debounced and
swipe style updates are limited to one per animation frame. Navigating away from
a pending worker inspection invalidates its result.

The composer has a bounded, model-only chip next to the mic. Its picker discovers
the connected provider's model catalog and offers only advertised reasoning levels.
Other providers still require connecting their account/key in Settings. The app's
credential store currently has one active chat provider. A three-dot indicator is
visible while the current chat is busy, with reduced-motion support. Touch feedback
uses the control's own shape, with the WebView tap highlight disabled.

Validation: `npm run test:ui` covers refresh coalescing, trailing updates,
cancellation/recovery, bounded model labels, reasoning metadata, and the served
module graph. Browser QA with a mocked native bridge and 120 long messages verified
100 runtime notifications produced one snapshot read, busy/idle dot visibility,
model/effort saving, and repeated chat/thread/menu navigation. This is functional
browser coverage, not a phone FPS measurement. Android compilation and real-device
menu/gesture/keyboard performance still require CI and phone-side validation.


### Composer model popover (follow-up branch)

The model chip opens a compact panel anchored 8px above the composer, rather
than a centered dialog. Its model-and-effort header toggles a bounded scrollable
model list; selecting a model or releasing the effort slider saves directly.
The blue slider displays only the selected model's advertised effort levels.
The panel follows composer/viewport resizing and closes on outside taps or Back.
Successful catalog discovery is cached in memory per provider for the mounted UI;
concurrent requests share discovery, failures can retry, and connection changes
invalidate the cache. No credentials or catalog data are written to browser storage.

Browser checks with the native-bridge fixture verified header/list interaction,
automatic effort saving, one discovery across repeated opens, and a 70-model list
bounded to 240px with internal scrolling. Native phone keyboard behavior still
needs device validation. `npm run test:ui` also covers cache reuse and retry.
