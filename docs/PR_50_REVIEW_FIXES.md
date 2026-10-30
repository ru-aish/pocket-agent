# PR #50 review fixes

Review baseline: `f68f6f5dc767995458425c6c4aa6ac13ad9a9805`.
All 73 inline review threads were checked, including the two already resolved threads. Two additional reports were already addressed on the baseline; the remaining reports are covered by this change.

| Review comment | Resolution |
| --- | --- |
| [preview/settings.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797559) | Retain discovered capabilities in the settings picker and disable unsupported efforts. |
| [preview/connection.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797565) | Require a native confirmation before disconnecting a login shared by voice and chat. |
| [preview/chat-view.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797574) | Remove archive controls before reconciling live message articles; add DOM coverage. |
| [preview/provider-service.mjs](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797585) | Persist the server-discovered catalog in the encrypted vault and reject unverified non-low Codex efforts. |
| [preview/app.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797591) | Check message count, individual lengths, and total characters before persisting a user turn. |
| [preview/test/server.test.mjs](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797598) | Fetch the root HTML and verify its module script before traversing the served dependency graph. |
| [runtime/test/chat-title.test.ts](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797607) | Include HTML-wrapped title text in the sanitizer regression. |
| [runtime/test/chat-title.test.ts](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797636) | Assert the configured Nebius bearer header at the HTTP seam. |
| [preview/test/provider-service.test.mjs](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797643) | Assert both exact Deepgram URLs in request order. |
| [preview/test/provider-service.test.mjs](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797647) | Assert that neither access nor refresh tokens appear in the encrypted file. |
| [app/src/main/java/dev/pocket/agent/PocketIcon.java](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797657) | Preserve drawable alpha and combine it with each paint color's alpha. |
| [preview/settings.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797666) | Show the Android-only Hybrid note without an unused browser key form. |
| [preview/README.md](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797674) | Document Node.js >=22.19.0 before installation. |
| [app/src/main/java/dev/pocket/agent/AccessibilityProxyBackend.java](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797679) | Use the validation IPC deadline for keyboard preparation. |
| [docs/MOBILE_UI_VALIDATION.md](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797685) | Make companion test commands runnable from the repository root. |
| [.github/workflows/mobile-ui-checks.yml](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797691) | Include script, control-core, root Gradle, and wrapper inputs in workflow paths. |
| [preview/index.html](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797699) | Update the native microphone's aria-pressed state from recording events. |
| [app/src/main/assets/tools.json](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797703) | Restore the snapshot/display-target matching requirement. |
| [app/src/main/assets/tools.json](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797710) | Restrict the private Store-session description to virtual displays. |
| [app/src/androidTest/java/dev/pocket/agent/NativeUiDeviceTest.java](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797720) | Delete fixture archive rows and progress records before restoring original state. |
| [DESIGN.md](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797726) | Already implemented on the PR head: loadHistory passes the current thread ID to renderChatHistory, which sets aria-current; each drawer opening reloads history. |
| [DESIGN.md](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797734) | Include run number, actual display lease status, and an Open worker label on worker cards. |
| [runtime/src/runtime.ts](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797738) | Reject chat switches and new chats while the pending inbox is nonempty; verify queue preservation. |
| [runtime/src/runtime.ts](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797742) | Record autonomous worker ownership immediately at reservation without using the interactive start budget. |
| [runtime/src/provider.ts](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797748) | Bound error-body idle reads to five seconds and honor the caller/deadline signal. |
| [runtime/src/codex-errors.ts](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797752) | Recognize both word orders of invalid encrypted-content messages. |
| [app/src/main/java/dev/pocket/agent/DisplayTaskSnapshot.java](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797758) | Extract package and visibility evidence from actual ActivityRecord entries in the activities fallback. |
| [app/src/main/java/dev/pocket/agent/MainActivity.java](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797762) | Emit an inactive voice event when stopping Groq capture. |
| [app/src/main/java/dev/pocket/agent/MainActivity.java](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797772) | Replace both new String.isBlank calls with trim().isEmpty. |
| [app/src/main/java/dev/pocket/agent/MainActivity.java](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797774) | Save personal instructions through an acquired RuntimeOwner and acknowledge only an accepted serialized result. |
| [app/src/main/java/dev/pocket/agent/GroqSpeechInput.java](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797780) | Check cancellation immediately after publishing the HTTP call, cancel it, and clear the WAV buffer. |
| [preview/vault.mjs](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797785) | Clean temporary credential snapshots even when write, sync, or close fails. |
| [preview/vault.mjs](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797788) | Sync a temporary key and atomically hard-link it into place without replacing a concurrent creator. |
| [preview/vault.mjs](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797790) | Update in-memory vault state immediately after rename, before directory sync. |
| [preview/server.mjs](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797796) | Limit the SSE branch to GET and test finite HEAD behavior. |
| [preview/voice.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797799) | Guard delayed audio.play failure text with the voice generation. |
| [preview/voice.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797801) | Guard all recorder data/error/stop/timer callbacks with the recording generation; add a cancellation regression. |
| [preview/app.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797808) | Already resolved on the PR head; title-aware sidebar search remains intact. |
| [preview/native-app.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797816) | Update local wake/autonomy preferences after successful native actions. |
| [preview/native-app.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797822) | Surface active workers and main/private-display status inline in chat. |
| [app/src/main/java/dev/pocket/agent/ShellDisplayService.java](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797826) | Register the frame listener before publishing a display and release resources if setup fails. |
| [preview/native-app.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797837) | Retry viewer frame failures while the viewer remains open. |
| [runtime/src/runtime.ts](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797844) | Abort and clear title generation on both main-thread transitions. |
| [app/src/main/java/dev/pocket/agent/ShellDisplayService.java](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797849) | Close arriving frames after display teardown using a synchronized closed flag. |
| [runtime/src/chat-title.ts](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797853) | Use one canonical title sanitizer for the Android runtime and companion. |
| [runtime/test/display-pool.test.ts](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797856) | Assert the queued lease receives a numeric display ID. |
| [app/src/androidTest/java/dev/pocket/agent/ConversationHistoryDeviceTest.java](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797866) | Assert the first and last message text of both archive pages. |
| [preview/chat-titles.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797872) | Strip trailing Markdown markers as well as leading markers. |
| [preview/style.css](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797875) | Restore responsive Georgia welcome-heading typography. |
| [runtime/src/provider.ts](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797877) | Document the ten-minute overall and five-minute stream idle limits. |
| [runtime/src/chat-title.ts](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4406797884) | Repeat final quote/Markdown cleanup after removing the optional title prefix. |
| [.github/workflows/apk.yml](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4413365573) | Require successful provenance collection before uploading APK artifacts. |
| [app/src/androidTest/java/dev/pocket/agent/SubscriptionVoiceActivationDeviceTest.java](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4413767067) | Add a separate MainActivity regression with an injected retained invitation before a direct voice tap. |
| [app/src/androidTest/java/dev/pocket/agent/SubscriptionVoiceActivationDeviceTest.java](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4413767078) | Already resolved on the PR head; the DOM-ready polling remains intact. |
| [preview/native-app.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4413767087) | Already fixed on the PR head: NativeUiDeviceTest expects Start Codex voice. |
| [preview/native-app.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4414584444) | Keep the global preferred effort distinct from per-model fallback and update it only on explicit choices. |
| [preview/model-picker.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4414584457) | Only close the picker when the completing save belongs to its current generation. |
| [preview/test/model-picker.test.mjs](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4414584473) | Assert the exact truncated model-chip text including its ellipsis. |
| [preview/model-picker.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4414584487) | Initialize the model trigger's aria-expanded state to false. |
| [preview/drawer-swipe.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4414584493) | Flush the most recent swipe offset before canceling its animation frame and clearing drag styles. |
| [preview/model-picker.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4414584523) | Refresh the model chip on provider/model changes in both Settings implementations. |
| [.github/workflows/mobile-ui-checks.yml](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4415040849) | Generate checksums relative to the APK artifact root for only the preserved APKs. |
| [preview/style.css](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4415040881) | Resize/reclassify the companion composer after programmatic draft assignments. |
| [preview/native-app.js](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4415040892) | Make overlapping refresh callers await the active read and perform a subsequent read. |
| [.github/scripts/run-mobile-ui-integration-tests.sh](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4415040906) | Restore or delete the prior hidden-API setting on exit, failure, SIGINT, and SIGTERM. |
| [scripts/test_shell_terminal.py](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4417344361) | Create the javac destination directory before compilation. |
| [app/src/main/java/dev/pocket/agent/ShellDisplayService.java](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4417344370) | Reject shell capture targets that differ from the caller's assigned display. |
| [scripts/ShellTerminalPhoneSmoke.java](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4417344400) | Require an exited session with exit code zero before smoke-test success. |
| [runtime/src/tools.ts](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4417344406) | Apply the standing shell grant to nested batch authorization. |
| [runtime/src/tools.ts](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4417344419) | Acquire a display for implicit shell captures nested in batches; add regression coverage. |
| [app/src/main/java/dev/pocket/agent/AgentDisplayCoordinator.java](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4417344439) | Track running shell requests and active sessions, serialize status observations, and release idle clients after sessions finish. |
| [app/src/main/java/dev/pocket/agent/NativeTools.java](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4417344445) | Route shell acquire/release through the display lease path. |
| [runtime/src/prompts.ts](https://github.com/ru-aish/pocket-agent/pull/50#discussion_r4417344455) | Explicitly route authorized physical-screen work through Accessibility without acquiring a private display. |


Validation on the revised source:

- 204 runtime tests, 28 companion/UI tests, and 20 Python/Java host tests passed.
- The runtime bundle built successfully.
- Android debug application and instrumentation Java sources compiled successfully with Android SDK 36 and JDK 21.
- Release lint passed.
- Shell syntax and Git whitespace checks passed.

Device instrumentation suites were compiled but not executed on an emulator in this session. Real provider accounts, microphone audio, and physical phones were not used for validation.
