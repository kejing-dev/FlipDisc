#!/usr/bin/env bash
# tools/evidence.sh [PLAN] : fresh install, cold-start default view (Placement.right, camera untouched), then every state
# of the plan with the window reopened on the left (`panelat left`, framing only) into
# spatial-app-factory/apps/flip-disc/build/$VERDIR. Run from the project root after the final build. DEV=emulator-5556.
set -u
DEV="${DEV:-emulator-5556}"; export DEV
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb.exe}"
B=../spatial-app-factory/apps/flip-disc/build
V=$B/${VERDIR:-v010}
PLAN="${1:-}"
mkdir -p "$V"
"$ADB" -s "$DEV" install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
"$ADB" -s "$DEV" shell pm clear io.embodit.flipdisc >/dev/null
"$ADB" -s "$DEV" logcat -c
"$ADB" -s "$DEV" shell monkey -p io.embodit.flipdisc -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
for i in $(seq 1 40); do "$ADB" -s "$DEV" logcat -d -s FlipDisc | grep -q "PADS + DISCS built" && break; sleep 2; done
sleep 8
echo '[{"name":"default-view","steps":[],"crop":"full"}]' > "$V/.p0.json"
python tools/shots.py "$B" "$V/.p0.json"
cp "$B/default-view.png" "$B/first-screen.png"
tools/fd.sh panelat left; sleep 9
[ -n "$PLAN" ] && python tools/shots.py "$V" "$PLAN"
"$ADB" -s "$DEV" logcat -d -s FlipDisc > "$V/logcat.txt"
echo "OVERFLOW lines: $(grep -c OVERFLOW "$V/logcat.txt")"
echo "FATAL / ANR: $("$ADB" -s "$DEV" logcat -d | grep -cE 'FATAL EXCEPTION|ANR in io.embodit')"
