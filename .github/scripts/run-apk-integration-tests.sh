#!/usr/bin/env bash
set -euo pipefail
mkdir -p emulator-diagnostics
cp app/build/outputs/apk/release/app-x86_64-release.apk emulator-diagnostics/Pocket-Agent-x86_64.apk
cp app/build/outputs/apk/androidTest/release/*.apk emulator-diagnostics/Pocket-Agent-tests.apk
cp fixture/build/outputs/apk/debug/*.apk emulator-diagnostics/Pocket-Agent-fixture.apk
provider_status=0
codex_status=0
./gradlew --no-daemon :app:connectedReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.pocket.agent.ProviderProfilesDeviceTest || provider_status=$?
mkdir -p emulator-diagnostics/provider-results
cp -R app/build/outputs/androidTest-results/connected/release emulator-diagnostics/provider-results/
./gradlew --no-daemon :app:connectedReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.pocket.agent.CodexAuthDeviceTest || codex_status=$?
mkdir -p emulator-diagnostics/codex-provider-results
cp -R app/build/outputs/androidTest-results/connected/release emulator-diagnostics/codex-provider-results/
if [ "$provider_status" -ne 0 ] || [ "$codex_status" -ne 0 ]; then exit 1; fi
python3 scripts/emulator_test.py --serial "$(adb get-serialno)" --artifacts emulator-diagnostics --fresh --diagnostics --class VoiceInvitationDeviceTest --class AutonomyDeviceTest --class VoicePipelineDeviceTest --class VoiceAssistantDeviceTest --class SubscriptionVoiceActivationDeviceTest --class SubscriptionVoiceContextDeviceTest --case 'LifecycleDeviceTest#mainThreadPromptAndDebugControlsWorkOnDevice' --case 'RuntimeDeviceTest#physicalDisplayCanLaunchObserveAndActWithoutVirtualLease'
adb uninstall dev.pocket.agent || true
adb uninstall dev.pocket.agent.test || true
# PhoneTargetDeviceTest exercises the same hidden WindowManager IME policy used by
# the privileged Shizuku shell UserService. Enable non-SDK access only on this
# disposable emulator so instrumentation can reach that platform API too.
adb shell settings put global hidden_api_policy 1
mcp_status=0
timeout 300 ./gradlew --no-daemon -PvoiceEmulatorTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.pocket.agent.ChatGptMcpDeviceTest || mcp_status=$?
mkdir -p emulator-diagnostics/chatgpt-mcp-results
cp -R app/build/outputs/androidTest-results/connected/debug emulator-diagnostics/chatgpt-mcp-results/
if [ "$mcp_status" -ne 0 ]; then exit "$mcp_status"; fi
timeout 300 ./gradlew --no-daemon -PvoiceEmulatorTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.pocket.agent.PhoneTargetDeviceTest
timeout 300 ./gradlew --no-daemon -PvoiceEmulatorTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.pocket.agent.CrashResilienceDeviceTest
adb shell settings delete global hidden_api_policy
timeout 300 ./gradlew --no-daemon :android-platform:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.pocket.android.NativeAndroidApisTest#unavailableTraversalChildFailsClosed
adb uninstall dev.pocket.agent || true
adb uninstall dev.pocket.agent.test || true
timeout 300 ./gradlew --no-daemon -PdevEmulatorTest :app:connectedDevelopmentAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.pocket.agent.DevBridgeDeviceTest
