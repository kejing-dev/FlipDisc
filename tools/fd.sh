#!/usr/bin/env bash
# Flip Disc harness: tools/fd.sh <cmd> [arg]   ·   tools/fd.sh shot <name.png>   ·   tools/fd.sh install
# DEV=emulator-5556 by default. Debug broadcast commands: see AGENTS.md.
DEV=${DEV:-emulator-5556}
ADB=${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}
PKG=io.embodit.flipdisc
case "$1" in
  install) "$ADB" -s "$DEV" install -r app/build/outputs/apk/debug/app-debug.apk && "$ADB" -s "$DEV" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null ;;
  shot) pico-cli capture screenshot -d "$DEV" -o "$2" --replace ;;
  log) "$ADB" -s "$DEV" logcat -d -s FlipDisc ;;
  *) "$ADB" -s "$DEV" shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "$1" --es a "'${2:-}'" >/dev/null ;;
esac
