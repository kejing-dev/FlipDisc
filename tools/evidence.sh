#!/usr/bin/env bash
# tools/evidence.sh [PLAN] : fresh install, cold-start default view (Placement.right, camera untouched), every state of the
# plan with the window reopened on the left (`panelat left`, framing only) into spatial-app-factory/apps/flip-disc/build/
# $VERDIR, the S00 loading frame, then the gate files (settings first screen, new-game confirm, quit + recents).
# Run from the project root after the final build. DEV=emulator-5556.
set -u
DEV="${DEV:-emulator-5556}"; export DEV
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb.exe}"
B=../spatial-app-factory/apps/flip-disc/build
V=$B/${VERDIR:-v010}
PLAN="${1:-}"
TMP=$(mktemp -d)
mkdir -p "$V"
launch() { "$ADB" -s "$DEV" shell am force-stop io.embodit.flipdisc; "$ADB" -s "$DEV" shell monkey -p io.embodit.flipdisc -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }
"$ADB" -s "$DEV" install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
"$ADB" -s "$DEV" shell pm clear io.embodit.flipdisc >/dev/null
"$ADB" -s "$DEV" logcat -c
launch
for i in $(seq 1 40); do "$ADB" -s "$DEV" logcat -d -s FlipDisc | grep -q "PADS + DISCS built" && break; sleep 2; done
sleep 10
echo '[{"name":"default-view","steps":[],"crop":"full"}]' > "$TMP/p0.json"
python tools/shots.py "$B" "$TMP/p0.json"
echo '[{"name":"first-screen","steps":[["sleep",3]],"crop":"full"}]' > "$TMP/p1.json"
python tools/shots.py "$B" "$TMP/p1.json"
tools/fd.sh panelat left; sleep 9
[ -n "$PLAN" ] && python tools/shots.py "$V" "$PLAN"
"$ADB" -s "$DEV" logcat -d -s FlipDisc > "$V/logcat.txt"
echo "OVERFLOW lines: $(grep -c OVERFLOW "$V/logcat.txt")"
grep BLANK "$V/logcat.txt" | awk '{print $6, $7, $9}' | sort -u > "$V/blank.txt"
[ -f "$V/G-settings-win.png" ] && cp "$V/G-settings-win.png" "$B/settings-firstscreen.png"
[ -f "$V/G-newgame-win.png" ] && cp "$V/G-newgame-win.png" "$B/newgame-confirm.png"
# S00: keep the loading stand-in 10 s on the next launch
tools/fd.sh s00hold on; launch; sleep 6
echo '[{"name":"S00","steps":[],"crop":"full"}]' > "$TMP/p2.json"
python tools/shots.py "$V" "$TMP/p2.json"
tools/fd.sh s00hold off; sleep 16
# quit through the real path (the confirm card's own exit lambda), then the recents dump
tools/fd.sh goto "S10 exit"; sleep 5; tools/fd.sh confirm; sleep 6
echo "pid after quit: '$("$ADB" -s "$DEV" shell pidof io.embodit.flipdisc)'"
"$ADB" -s "$DEV" shell dumpsys activity recents > "$B/exit-recents.txt"
echo "visible recents with package: $(grep -E 'Recent #[0-9]+:' "$B/exit-recents.txt" | grep -c io.embodit.flipdisc)"
echo "FATAL / ANR: $("$ADB" -s "$DEV" logcat -d | grep -cE 'FATAL EXCEPTION|ANR in io.embodit')"
