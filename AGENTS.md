# 黑白棋 Flip Disc: project guidance

Reversi for PICO OS 6 Shared Space, PICO Spatial SDK 6.1.9, package `io.embodit.flipdisc`, v0.1.0.
Design contract: spatial-app-factory `apps/flip-disc/design-package.json` (v1.3, profile A12 main loop only; `v2[]` is not
built: no record import / export, no endgame puzzles, no skins, no coach, no hover preview, no redo, no resign).
Code skeleton taken from kejing-dev/FiveStones v0.1.5 (itself from CloudAir v1.0.2: two containers, theme, sound pool,
LevelGate, window control); the Reversi domain, scene, drag and window pages are new.

## Surfaces
- **Board volume** (default container `FlipBoard`, Volumetric 0.94 x 0.80 x 0.88 m, Fixed scale, gravity aligned):
  walnut frame 0.69 m + green felt (Canvas grid), tipped 30° (20 / 30 / 45 / 60 / 90) about its near edge; 64 click pads
  `pad:<i>` (6.15 cm, Collision + Interactable + HoverEffect) each with a visible 4 cm tile `tile:<i>`; 64 two-colour discs
  `piece:<i>` (black half `disc:<i>:top`, ivory half `disc:<i>:bottom`; white side up = rotated 180° about X); two walnut
  troughs `trough:black|white` (left / right) with 30 standing discs `slot:<c>:<k>` each; legal rings, snap ring, hint
  mark (solid / dashed / grey textured plane), illegal frame, last-move dot; the seat tag (AttachmentPanel 280 x 60 dp drawn
  1.65x, upright above the far edge).
- **Control window** (`FlipControl`, Planar 560 x 600 dp fixed, `Placement.right(board)`): native SpatialUI `TabBar`
  with 3 pages Play / Rules / Settings. Play = setup (S02) / game (S03–S08) / result (S09); Settings › More settings (S12)
  and the confirm cards (S10) are pushed pages. Opened through `LevelGate` (D9; Shared Space has no head pose, so it opens
  on the 1.5 s fallback). More settings › [Reopen panel] closes, waits for ON_DESTROY, reopens to the right.

## Code
| Path | What |
|---|---|
| `domain/Board.kt`, `Rules.kt` | bitboards, legal moves, flips (+ per-direction distance list for the domino), passes, end, perft |
| `domain/Engine.kt` | negamax α-β + iterative deepening + time limit, 6 levels (`Level.ALL`), exact endgame, hint (3 kinds) |
| `domain/Drag.kt` | snap hysteresis (4.8 / 6.4 cm, 1 cm switch, face 12 / 16 cm), `DragController` (held disc, outside Compose), flip timing |
| `domain/GameFlow.kt` | the single state (`PlayState`), phases S00–S09, drag / tap / AI / pass / undo / hint / save |
| `domain/BoardGeometry.kt` | metres: tilt, squares, troughs, flight arcs, seat tag, D35 envelopes, `Q4` quaternions |
| `ui/board/*` | SpatialView + `BoardScene` (entities; per-frame motion in `BoardFrameSystem`), textures, resources, input targets |
| `ui/control/*` | the control window pages |
| `platform/*` | Application / graph, SoundPlayer, WindowCtl; debug builds add `DebugCommandReceiver` |

Rules - state - render: the scene only renders `PlayState` (+ the held disc from `DragController`, read each frame); every
input goes through `GameFlow.tapSquare / tapTrough / dragStart / dragMove / dragEnd / dragCancel`, the debug commands too.
3D input = factory template `ui/board/input/BoardInput.kt` (D50; DragTotal sums SDK 6.1.9 per-event drag deltas) +
`FlipTargets` / `BoardNames` (names -> square / trough, replay-tested in HitReplayTest). Animations run on wall clock (D55).

## Build / test (macOS kj-mbp-m5 or Windows kj-alienware)
```bash
# macOS: export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home   (Android SDK ~/Library/Android/sdk)
# Windows (Git Bash): export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.12.1+1 JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT
./gradlew assembleDebug :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests '*AiStrengthTest*' -DaiStrength=true   # slow AI acceptance (M4)
python tools/build_icon.py --icon512 ../spatial-app-factory/apps/flip-disc/icon.png
python tools/loudness.py ../spatial-app-factory/apps/flip-disc/qa/loudness-v0.1.0.wav
tools/evidence.sh PLAN.json     # fresh install, default view, every state (zh / en) into factory apps/flip-disc/build/v010
```
Offline release build: add `-x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease` if lint is not cached.

## Debug broadcast (QA screenshots)
`adb -s emulator-5556 shell am broadcast -a io.embodit.flipdisc.DEBUG -p io.embodit.flipdisc --es cmd <cmd> --es a "'<arg>'"`
(or `tools/fd.sh <cmd> [arg]`). Log tag `FlipDisc`; input log `adb shell run-as io.embodit.flipdisc cat files/input-log.txt`.

**Screenshots on this emulator**: `screencap` / `pico-cli capture` return half-white frames while the emulator window is in
the background; `tools/shots.py` uses `adb emu screenrecord screenshot`. The AVD has no camera pose injection
(`pvrtracking_inject` missing), and the window opens at the right edge of the default view, so for whole-window shots
send `panelat left` first (debug only; reopens the window left of the board). `panelat right` goes back.

| State | Command (pre-state) | Notes |
|---|---|---|
| S00 准备中 | `s00hold on`, force-stop + launch, capture | felt slab + 4 discs, loading page; `s00hold off` after |
| S01 第一次 | `goto S01` | rules page + [Got it, start a game] |
| S02 开局设置 | `goto S02`; two players: `setupmode 2p` then `goto S02` | |
| S03 轮到你 | `goto S03` (starts AI L3 if no game) | legal rings, seat tag "Your move" + tip on the first move |
| S03x 不能落 | `goto S03x` (taps a1, keeps the frame + reason); `hold off` releases | |
| S04a 拖着吸住 | `goto S04a` (drags the side to move's disc over its first legal square and holds) | `hold off` releases = plays it |
| S04b 拖着没对上 | `goto S04b` | `hold off` releases = disc flies back |
| S05 翻面 | `goto S05` (freezes the next move mid-flip of ring 1); `hold off` | |
| S06 对方在想 | `goto S06` (new game, you White, AI held) ; `hold off` | |
| S07 没棋可下 | `goto S07` (replays a random game to a real pass, holds it); `hold off` | |
| S08a/b/c 提示 | `goto S08a` / `S08b` / `S08c` (look only); real hint: `hint` | |
| S09 终局 | `goto S09 win` / `lose` / `draw` (random full games loaded through the flow) | |
| S10 确认卡 | `goto S10 new` / `undo` (2p) / `reset` / `exit`; `confirm` / `cancel` | `confirm` on exit runs the real quit path |
| S11 / S12 / S13 | `goto S11` / `goto S12` / `goto S13`; how to play: `rulesview 1` | |

Other: `place f5`, `drag "f5 [hold]"`, `drag "free hold"`, `new "black ai 3"` / `"white 2p 1"`, `load "f5 d6 c3"`, `undo`,
`tilt 20|30|45|60|90`, `lang zh|en|system`, `sound 0-3`, `reopen`, `seed N`, `page play|rules|settings`, `more on|off`,
`replay "f5|trough:black [hand-ray|eye-hand|controller]"` (real entities -> resolveUp -> the real listener, writes
`INPUT tap … -> square=` to input-log), `dump`, `evidence` (pads / discs / tag / collider sizes).
