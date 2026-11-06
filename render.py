#!/usr/bin/env python3
"""Render a Flashback scene without clicks: writes the autorender request, starts LabyMod, waits for the result.

    render.py gridworld drueber [-o out.mp4] [--width 1920 --height 1080 --fps 60 --bitrate 20m]
"""
import argparse
import os
import json
import shutil
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

DATA = Path.home() / ".minecraft" / "flashback"
REQUEST = DATA / "autorender.json"
RESULT = DATA / "autorender-result.json"
# The Flatpak only sees ~/.minecraft, so the game writes here and this script moves the file out.
EXPORTS = DATA / "exports"


def game_running():
    # Only a java argv counts: a shell whose command line merely mentions the main class must not match.
    for cmdline in Path("/proc").glob("[0-9]*/cmdline"):
        try:
            argv = cmdline.read_bytes().split(b"\0")
        except OSError:
            continue
        if argv[0].endswith(b"java") and b"net.minecraft.launchwrapper.Launch" in argv:
            return True
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("replay", help="replay name in ~/.minecraft/flashback/replays or a path to the zip")
    ap.add_argument("scene")
    ap.add_argument("-o", "--output", type=Path, help="default: ~/Downloads/<replay>-<scene>-<time>.mp4")
    ap.add_argument("--timeout", type=int, default=1800, help="seconds for the whole run")
    ap.add_argument("--width", type=int)
    ap.add_argument("--height", type=int)
    ap.add_argument("--fps", type=float)
    ap.add_argument("--bitrate", help='Flashback syntax, e.g. "20m"')
    ap.add_argument("--instance", default=os.environ.get("AUTORENDER_LABYMOD_INSTANCE"),
                    help="LabyMod instance id (or env AUTORENDER_LABYMOD_INSTANCE)")
    a = ap.parse_args()

    if not a.instance:
        sys.exit("set --instance or AUTORENDER_LABYMOD_INSTANCE to your LabyMod instance id")
    replay = Path(a.replay).expanduser()
    if not replay.suffix:
        replay = DATA / "replays" / f"{a.replay}.zip"
    replay = replay.resolve()
    if not replay.is_file():
        sys.exit(f"replay not found: {replay}")
    if game_running():
        sys.exit("Minecraft is already running; close it and try again")

    stamp = datetime.now().strftime("%Y-%m-%dT%H-%M-%S")
    name = f"{replay.stem}-{a.scene}-{stamp}.mp4"
    final = (a.output or Path.home() / "Downloads" / name).expanduser().resolve()
    staged = EXPORTS / name

    settings = {k: v for k, v in {"width": a.width, "height": a.height,
                                   "framerate": a.fps, "bitrate": a.bitrate}.items() if v is not None}
    RESULT.unlink(missing_ok=True)
    REQUEST.write_text(json.dumps({"replay": str(replay), "scene": a.scene, "output": str(staged),
                                   "timeoutSeconds": a.timeout, "settings": settings}, indent=2))

    # The Flatpak's run.sh drops its arguments, so call the launcher binary directly. If the launcher
    # is already open, Electron hands the arguments to that window, which quick-launches the instance.
    subprocess.Popen(["flatpak", "run", "--command=zypak-wrapper", "net.labymod.LabyMod",
                      "/app/extra/launcher/labymodlauncher", "--no-update-check", f"--instance={a.instance}"],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    print(f"launched LabyMod instance {a.instance}, rendering {replay.name} / {a.scene}", flush=True)

    deadline = time.monotonic() + a.timeout + 300
    seen_game = False
    while not RESULT.exists():
        running = game_running()
        seen_game |= running
        if seen_game and not running:
            time.sleep(2)
            if RESULT.exists():
                break
            fail("Minecraft exited without a result (crash?); see the instance's logs/latest.log")
        if time.monotonic() > deadline:
            fail("timed out waiting for the render")
        time.sleep(3)

    for _ in range(40):
        if not game_running():
            break
        time.sleep(3)
    result = json.loads(RESULT.read_text())
    for line in result.get("log", []):
        print("  " + line)
    if not result["ok"]:
        fail(result["message"])
    final.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(staged, final)
    print(final)


def fail(message):
    REQUEST.unlink(missing_ok=True)
    sys.exit(f"render failed: {message}")


if __name__ == "__main__":
    main()
