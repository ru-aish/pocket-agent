#!/usr/bin/env bash
set -uo pipefail

app_id=dev.pocket.agent
microphone_permission=android.permission.RECORD_AUDIO
connected_results=app/build/outputs/androidTest-results/connected
evidence_root=app/build/outputs/voice-emulator-test-evidence
had_microphone_permission=false

rm -rf "$evidence_root"
mkdir -p "$evidence_root"

# Preserve the permission on a developer's already-installed app as well as
# on a freshly installed CI app. Cleanup runs on failure or interruption.
if adb shell pm path "$app_id" | grep -q '^package:' &&
  adb shell dumpsys package "$app_id" | grep -Eq 'android[.]permission[.]RECORD_AUDIO: granted=true'; then
  had_microphone_permission=true
fi

restore_microphone_permission() {
  local status=$?
  trap - EXIT
  if adb shell pm path "$app_id" | grep -q '^package:'; then
    if [[ "$had_microphone_permission" == true ]]; then
      adb shell pm grant "$app_id" "$microphone_permission" || status=1
    else
      adb shell pm revoke "$app_id" "$microphone_permission" || status=1
    fi
  fi
  exit "$status"
}
trap restore_microphone_permission EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

run_test() {
  local test_class="$1"
  local test_name="${test_class##*.}"
  local status=0
  ./gradlew --no-daemon :app:installDebug -PvoiceEmulatorTest=true || return 1
  adb shell pm grant "$app_id" "$microphone_permission" || return 1
  ./gradlew --no-daemon :app:connectedDebugAndroidTest -PvoiceEmulatorTest=true \
    "-Pandroid.testInstrumentationRunnerArguments.class=${test_class}" || status=$?
  mkdir -p "$evidence_root/$test_name"
  if [[ -d "$connected_results" ]]; then
    cp -a "$connected_results/." "$evidence_root/$test_name/" || status=1
  else
    echo "Missing connected test results for $test_class" >&2
    status=1
  fi
  return "$status"
}

result=0
for test_class in \
  dev.pocket.agent.InstantVoiceCaptureDeviceTest \
  dev.pocket.agent.HybridSttDeviceTest \
  dev.pocket.agent.VoiceSttSettingsDeviceTest \
  dev.pocket.agent.JarvisDetectorDeviceTest \
  dev.pocket.agent.JarvisWakeLifecycleDeviceTest \
  dev.pocket.agent.WakeWordSettingsDeviceTest; do
  run_test "$test_class" || result=1
done
exit "$result"
