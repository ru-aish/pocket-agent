# General on-phone shell

`phone_shell` is available to the main assistant and every subagent, including researchers, nested workers and autonomous runs. It executes arbitrary shell code locally through the existing Shizuku UserService under Android shell UID. It has no command allowlist, display filter, or per-command approval dialog. Ordinary Android/SELinux shell permissions still apply; this is not automatically root.

No PC, USB cable, remote terminal server or ADB network connection is needed during use. Start Shizuku on the phone and authorize Pocket Agent. On an unrooted phone, Shizuku can be started through the phone's wireless-debugging pairing flow; it may need restarting after a reboot. Internet access for the configured model provider remains separate from local shell execution.

## Discover the environment

Call `phone_shell` with `{"action":"context"}`. It returns a persistent per-agent `working_directory`, a common `scripts_directory`, and the assigned display context if there is an active lease. Available binaries are device-dependent: Android supplies `sh` and system utilities, not this PC's Python/compiler/package environment. Agents can discover installed commands, write scripts, and use whatever programs shell permissions permit.

`{"action":"exec","command":"id; command -v sh; ls -l \"$POCKET_SCRIPTS_DIR\""}` starts an asynchronous shell session. The result contains `session_id`, status, paged output and `next_offset`. Poll with `{"action":"poll","session_id":"…","offset":0}` and then each returned `next_offset` until status is `exited` and `has_more` is false. Output combines stdout/stderr, offsets count bytes, and exit codes are reported independently of task success. The output file is also returned for use by scripts.

Each exec starts a fresh `/system/bin/sh`; use `cwd`, files and script arguments for persistent state. `cd` and environment changes inside one exec do not alter the next exec. Shell commands are not killed just because a tool call returns. `stop` signals the command shell directly; child processes may require their own inspection and termination. Shell sessions are independent of agent turns: stopping an agent does not promise to terminate its already-started shell programs. Android can kill the service or processes, and a reboot ends running programs. Saved scripts, output, and completed exit receipts remain on disk until explicitly removed. Missing exit information means unknown outcome, not a reason to repeat a mutation.

## Use a private display

1. `{"action":"acquire"}` acquires a display from the existing shared pool. No accessibility observation is needed.
2. `context` and shell results report the lease's display ID and 720×1280 coordinate space at 320 dpi. Exec also receives `POCKET_DISPLAY_ID`, `POCKET_DISPLAY_WIDTH`, `POCKET_DISPLAY_HEIGHT`, `POCKET_WORK_DIR`, `POCKET_SCRIPTS_DIR` and `POCKET_SHELL_HOME`.
3. Run general commands, for example `input -d "$POCKET_DISPLAY_ID" tap 240 170` or `am start --display "$POCKET_DISPLAY_ID" -f 0x18800000 -n package/activity`. Explicit display selection prevents accidental interference with the user's screen or another worker. This is guidance, not command filtering.
4. `{"action":"capture"}` returns the virtual display's compositor image directly to the model. It does not use accessibility, `phone_act` snapshot IDs, or their 15-second expiry. It can capture another existing frame reader with an explicit `display_id`; it does not enforce owner-only access. Use returned coordinate dimensions when an image was resized. Allow app transitions to settle and verify results.
5. `{"action":"image","path":"/shell/readable/image.png"}` returns an image created by a script. For a physical-screen screenshot a command can run `screencap -p` into a file, then use `image`. `screencap -d` may expect a physical SurfaceFlinger ID rather than a logical virtual display ID.
6. `{"action":"release"}` releases the agent's lease. Finish any scripts targeting that display first; release removes its temporary tasks through the existing cleanup path.

Main and all workers share shell permissions and access to shell-owned files. Each gets a separate default scratch directory and its own display lease for coordination, without an imposed command boundary. Commands can access other locations/devices when Android shell permissions permit it.

## Reuse code

The shared shell home is `/data/local/tmp/pocket-agent-shell`, with `scripts/`, `workspaces/`, and `sessions/`. Save reusable scripts under `scripts_directory`, validate them, and use `memory_save` to record the path, purpose, inputs and tested conditions. Use `skill` for a reusable validated procedure. Researchers and autonomous workers can save these notes. Script files are local shell-readable storage, not encrypted app memory or a backup; do not put credentials in scripts or memory notes.

## Validation

`runtime/test/shell.test.ts` covers shell exposure under all grants, arbitrary-command forwarding, independent image returns, shared display lifecycle, cancellation before dispatch, and standing autonomous shell access. The normal runtime suite remains applicable.

`python3 -m unittest scripts/test_shell_terminal.py -v` compiles production `ShellTerminal.java`, `Json.java` and `scripts/ShellTerminalSessionTest.java` with small host-only JSON value stubs. It checks that stopping a running script prevents its next command and that stopped or `exec`-replaced sessions retain exit receipts without further polling. Existing CI discovers this test automatically. The Java test can also run under Android `app_process` with real `org.json` and the default Android shell.

`scripts/ShellTerminalPhoneSmoke.java` exercises the production Java executor and frame-capture code under shell UID via `app_process`, without installing a test APK. Compile it with the production shell/display helper classes and generated `IDisplayShell` AIDL, dex the result, then launch with a fresh shell-writable result directory and optional activity component. It checks saved script reuse across agents, concurrent commands, nonzero exit codes, output/exit persistence, UTF-8 pagination and accessibility-independent display capture. This low-level test does not replace an installed-APK Shizuku/Binder/model test.
