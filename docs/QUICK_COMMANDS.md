# Local voice commands

Pocket Agent tries a bounded local command route before submitting voice input to the
existing main agent. Enable or disable **Handle supported voice commands locally**
in Settings; the choice applies to new voice sessions. It is enabled by default.
The existing STT modes and Deepgram speech output remain in use. This feature does
not make cloud speech recognition offline or remove its connection time.

## Understanding and resources

- Exact clock/date, device-status, calculator and unit-conversion requests use
  deterministic parsing. Time/date/math do not load the encoder or call phone tools.
- Action paraphrases use `sentence-transformers/all-MiniLM-L6-v2`, pinned revision
  `5641a7880f40ebf4035d05e60c5f9b7a9c272c84`, dynamic INT8 ARM64 ONNX weights:
  23,026,053 bytes, plus vocabulary and precomputed action prototypes. The app
  already depends on ONNX Runtime 1.21.0; no additional inference runtime or network
  provider is introduced. Weights are bundled, not downloaded on the phone.
- One worker thread owns parsing, inference and native dispatch. ONNX uses at most
  two intra-op CPU threads and one inter-op thread. The encoder loads lazily for
  the first non-exact command and closes when the voice session ends. It does not
  run continuously with the wake detector. Model size is not total memory usage;
  Android RAM, power and cold-load performance remain to be measured.
- The model compares normalized action phrases against prototypes. Dynamic app and
  contact names and dictated message content are removed from the classification
  representation so they cannot dominate intent matching. Exact original values
  remain in the extracted slots. Similarity must be at least 0.50 and the gap to
  the second intent at least 0.035; these are cosine scores, not probabilities.
- A full-clause English grammar validates arguments and up to four actions. The
  model cannot generate code, tools, recipients or parameters. This is a bounded
  semantic command router, not an unrestricted natural-language planner.
- No supported complete plan means the entire original utterance goes to the
  existing agent before any mutation. Local backend failures or uncertain action
  outcomes do not cause an agent to replay the request.

## Supported everyday requests

| Category | Examples | Direct path and limits |
| --- | --- | --- |
| Time | “What time is it?”; “What time is it in London?” | Device clock; named IANA city/zone or UTC. Unknown/ambiguous zones go to the agent. |
| Date/day | “What day is it?”; “What's today's date?” | Device date and timezone. |
| Battery/charging | “How much battery is left?” | Public battery broadcast; no permissions prompt. |
| Connectivity | “Am I online?”; “Am I on Wi-Fi?” | Active transport and Android-validated Internet access; no SSID/location access. |
| Volume/brightness/storage queries | “What is the volume?”; “Check my brightness”; “How much free storage is available?” | Media volume, stored brightness and free bytes on the app's storage volume. |
| Calculator | “Calculate 12 plus 35”; “What is 18 divided by 3?” | One numeric +, −, × or ÷ operation. Decimal numbers supported; no expression trees or general word problems. |
| Unit conversion | “Convert 5 miles to kilometers”; “What is 70 fahrenheit in celsius?” | km/miles/meters/feet, kg/pounds, Celsius/Fahrenheit; incompatible dimensions fall back. |
| Apps | “Could you bring up Spotify?”; “List my installed apps” | Launcher labels/package IDs from `phone_apps`; exact unambiguous installed-app resolution. Launches on the visible screen require Accessibility. |
| Web links | “Open https://example.com” | Validated HTTP(S) URL, without embedded credentials; visible-screen launch requires Accessibility. |
| Timer | “Set a timer for ten minutes”; “Start a timer” | `phone_timer`; missing duration asks a follow-up. Mixed hours/minutes/seconds up to 24 hours. Clock/OEM must accept the request. |
| Alarm | “Set an alarm for 7 am”; “Set an alarm” | `phone_alarm`; missing time asks. AM/PM or explicit 24-hour HH:MM, no recurring/date-specific alarms. |
| Clock pages | “Show the alarms screen”; “Open timers” | Standard Clock intent on the visible screen; Accessibility required. Does not claim to read or cancel existing timers. |
| Navigation | “Go home”; “Go back”; “Show me recent apps” | Fresh physical-display observation followed by a guarded `phone_act` key; Accessibility required. No vision model. |
| Contacts | “Look up the phone number for Rahul” | Contacts Provider; Contacts permission. Spoken lists use names and last four digits. |
| Calls | “Call Priya”; “Dial +14155550123” | Telecom; resolves contacts/numbers and asks for SIM if necessary. Calls permission required. Requested does not mean connected. |
| SMS | “Text Rahul saying I will be late” | SmsManager; contact lookup, number/SIM clarification and durable operation ID. Requires SMS permission and a valid/default SIM. Reports submitted, not delivered. |
| Unspecified messaging channel | “Send a message to Rahul saying hello” | Asks SMS or WhatsApp before recipient execution. |
| WhatsApp reply | “WhatsApp Rahul saying hello” | Only an app-provided current individual notification reply. Notification access required; always asks the user to select the actual conversation. No private WhatsApp protocol. |
| Notifications | “Read my notifications” | Bounded current-notification page, not chat history; Notification access required. |
| Calendars/events | “List my calendars”; “Read my events today” | Calendar Provider, device-local date boundaries; Calendar permission. Bounded list of titles. |
| Media | “Pause the music”; “Next track”; “Play music on Spotify” | Existing active MediaSession advertising the action; Notification access needed. Multiple matching sessions go to the agent. Does not select arbitrary songs. |
| Media volume | “Turn the volume up”; “Set volume to 35 percent”; “Mute the music” | AudioManager, read-back; media stream only. Does not change ringer or Do Not Disturb. |
| Brightness | “Set screen brightness to 60 percent” | Requires **Allow manual brightness control** / Android Modify system settings access. Explicitly switches to manual brightness. |
| Flashlight | “Turn on the torch”; “Switch the flashlight off” | CameraManager; Camera permission and available rear flash required. Another camera user or app process exit can turn it off. |
| Settings pages | “Open Wi-Fi settings”; “Show me Bluetooth settings” | Opens Wi-Fi, Bluetooth, display, sound, location, airplane-mode or battery settings on the explicit physical target through Accessibility; does not claim to toggle restricted settings. |

Permissions remain user-granted through **Choose phone permissions**, Notification
access settings, Accessibility settings, and the manual-brightness settings button.
Unsupported/OEM-denied operations produce an error rather than a success claim.

## Compound commands and clarification

“Can you open Spotify and set a timer for ten minutes?” becomes two typed actions.
The entire plan is parsed, classified and resolved before the first mutation.
Missing timer duration, channel, contact, number or SIM pauses the whole plan.
Execution is ordered and stops on the first native error; it is **not atomic**.
Every mutation uses a new installation-bound operation ID through `NativeTools`
and the existing `OperationStore`; uncertain results are never blindly replayed.

For “Text Rahul saying I'll be late”, multiple matches are spoken as a numbered
list with names and number suffixes. The next voice turn may be “first”, “the
second one”, “Rahul Mehta”, “first Rahul Shah”, or “number ending 1111”. Conflicting
ordinal/name answers and ambiguous names ask again. Multiple numbers belonging to
one person also ask. Full contact names are preferred over partial-name results; even a single partial match
requires explicit selection. Truncated contact searches require a narrower query rather than a guessed choice.

Clarifications are memory-only within the current voice service, expire after two
minutes, and keep the microphone available for 30 seconds after the prompt instead
of the usual three. “Cancel” discards the pending plan. An independent recognized
command abandons it. Process death or closing voice cannot resume an old send.
No new permission confirmation is imposed on an explicit, unambiguous SMS request.

Quoted message bodies are preserved. In compound requests, quote the message if it
contains action-like conjunctions: `Text Rahul saying "Open Spotify and set a timer"`.
An unquoted message body containing “and”, “then” or “also” goes to the agent
because the local grammar cannot establish whether it includes another action. Ordinary transcript punctuation is retained in
message bodies. The speech recognizer's own punctuation/wording can still differ
from the user's intended text.

Local outcomes appear in chat and normal saved history without another inference
turn. Local audit events also retain operation IDs. Logcat records only route and
elapsed milliseconds, not contacts, numbers or message bodies.

## Remaining native-tool inventory and boundaries

The audit covered every entry in `app/src/main/assets/tools.json`:

- Covered by the local route: `phone_apps`, `phone_open_app`, `phone_open_url`,
  `phone_contacts`, `phone_call`, `phone_calendars`, `phone_calendar_events`,
  `phone_alarm`, `phone_timer`, `phone_clock_screen`, `phone_sms`,
  `phone_notification_read`, `phone_notifications`, `phone_reply`, `phone_media`,
  `phone_device_info`, `phone_volume`, `phone_brightness`, `phone_flashlight`,
  `phone_settings`. `phone_observe`/`phone_act` are used only for navigation keys.
- Infrastructure: `phone_capabilities` supplies current SIM/session metadata and
  operation prefixes. `phone_operation` remains the durable result/receipt
  inspection tool; `phone_batch` and `phone_screen` remain agent infrastructure.
- `phone_calendar_create` is a direct Android API, but natural-language event
  creation stays with the existing agent because calendar/account choice, date,
  timezone, title and duration must be resolved together.
- Arbitrary screen clicks, settings toggles, new WhatsApp conversations, WhatsApp
  groups, notification titles that cannot identify a recipient, email, general
  reminders/automation, weather, navigation directions, search, news and open-ended
  requests retain the existing agent path. Its existing tool/permission constraints
  still apply. Fallback does not imply the task is guaranteed possible.

There is no claim to understand every phrasing or every possible phone action.
Missing grammar coverage and low semantic confidence deliberately fall back.

## Reproducible verification

```sh
python3 scripts/evaluate_quick_commands.py
# After intentional corpus or normalization changes:
python3 scripts/evaluate_quick_commands.py --generate
```

The script checksum-verifies bundled weights, vocabulary and prototype provenance;
downloads checksum-pinned host ONNX Runtime 1.21.0 and org.json jars into a user
cache; compiles the production Java code; and runs request plus dialogue/dispatch
checks. No phone, credentials, calls or actual messages are used.

The checked-in [host evaluation](validation/quick-command-evaluation-2026-10-28.json)
is a curated regression set, not an independent population accuracy estimate or
phone end-to-end benchmark. The suite tests positive paraphrases and compounds,
negative/out-of-scope requests, explicit intent and tool assertions, message-body preservation, missing parameters,
contact/number/SIM disambiguation, ordinal/name conflicts, cancellation, expiry,
WhatsApp notification selection/expiry, operation IDs and no replay after errors.

The APK Actions workflow runs this host evaluation and the new
`QuickCommandDeviceTest` on its emulator. The device tests load the actual bundled
INT8 model through Android ONNX Runtime, query read-only device info, and use fake
message backends. They never send real messages or calls. These newly added
instrumentation tests have not yet been executed on a device in this workspace.
Android Java compilation and host tests are separate from a signed APK build,
physical microphone/speaker tests, phone resource measurements and real transport
acceptance. APK compilation/signing remains a GitHub Actions workflow.
