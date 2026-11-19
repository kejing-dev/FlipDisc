"""Self-test / QA screenshot driver (debug builds): runs debug-broadcast steps, captures, crops window / board.

  python tools/shots.py OUTDIR plan.json
  plan: [{"name": .., "pose": "reset"|"win"|"board"|null, "steps": [[cmd, arg], ["sleep", s], ["adb", "shell ..."]], "crop": "win"|"board"|"full"}]

Captures with `adb emu screenrecord screenshot` (the emulator's own frame): on kj-alienware `screencap` / `pico-cli capture`
return half-white frames while the emulator window is in the background (dog-manners ui-audit note).
"""
import glob, json, os, subprocess, sys, time
from PIL import Image

DEV = os.environ.get("DEV", "emulator-5556")
import shutil


def _find_adb():
    if os.environ.get("ADB_EXE") or os.environ.get("ADB"):
        return os.environ.get("ADB_EXE") or os.environ.get("ADB")
    home = os.path.expanduser("~")
    for c in (shutil.which("adb"), os.path.join(home, "Library", "Android", "sdk", "platform-tools", "adb"),
              os.path.join(home, "Android", "Sdk", "platform-tools", "adb"),
              os.path.join(home, "Android", "Sdk", "platform-tools", "adb.exe"),
              os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")):
        if c and os.path.isfile(c):
            return c
    return "adb"


ADB = _find_adb()
PKG = "io.embodit.flipdisc"
POSES = {"reset": "", "win": "turn right 30 degrees then move forward 0.8 meters", "board": "move forward 0.9 meters"}
# "win" frames the control window after `panelat left` (emulator default view); "both" saves the full frame + NAME-win.png
CROPS = {"win": (0, 860, 740, 1740), "board": (700, 1000, 1900, 1900), "full": None}
pose_now = None


def cmd(c, a=""):
    subprocess.run([ADB, "-s", DEV, "shell", "am", "broadcast", "-a", PKG + ".DEBUG", "-p", PKG, "--es", "cmd", c, "--es", "a", "'%s'" % a], capture_output=True)


def pose(p):
    global pose_now
    if p is None or p == pose_now:
        return
    subprocess.run(["pico-cli", "camera", "reset", "-d", DEV], capture_output=True, shell=os.name == "nt")
    if POSES[p]:
        subprocess.run(["pico-cli", "camera", "move", "-d", DEV, "--text", POSES[p]], capture_output=True, shell=os.name == "nt")
    time.sleep(2)
    pose_now = p


def shot(path):
    d = os.path.dirname(os.path.abspath(path))
    before = set(glob.glob(os.path.join(d, "Screenshot_*.png")))
    subprocess.run([ADB, "-s", DEV, "emu", "screenrecord", "screenshot", d], capture_output=True)
    for _ in range(40):
        new = set(glob.glob(os.path.join(d, "Screenshot_*.png"))) - before
        if new:
            time.sleep(0.3)
            os.replace(new.pop(), path)
            return
        time.sleep(0.25)
    raise RuntimeError("no screenshot")


def main(out, plan):
    global pose_now
    os.makedirs(out, exist_ok=True)
    pose_now = os.environ.get("POSE")
    for item in json.load(open(plan, encoding="utf-8")):
        pose(item.get("pose"))
        for st in item.get("steps", []):
            if st[0] == "sleep":
                time.sleep(float(st[1]))
            elif st[0] == "adb":
                subprocess.run([ADB, "-s", DEV] + st[1].split(), capture_output=True)
            else:
                cmd(st[0], st[1] if len(st) > 1 else "")
                time.sleep(0.2)
        raw = os.path.join(out, item["name"] + "-raw.png")
        shot(raw)
        im = Image.open(raw)
        mode = item.get("crop", "both")
        if mode == "both":
            im.save(os.path.join(out, item["name"] + ".png"))
            im.crop(CROPS["win"]).save(os.path.join(out, item["name"] + "-win.png"))
        else:
            c = CROPS[mode]
            (im.crop(c) if c else im).save(os.path.join(out, item["name"] + ".png"))
        os.remove(raw)
        print("shot", item["name"], flush=True)


main(sys.argv[1], sys.argv[2])
