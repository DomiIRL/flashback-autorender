# flashback-autorender

A small Fabric client mod plus a driver script that render a [Flashback](https://modrinth.com/mod/flashback)
replay scene without anyone touching the game: open the replay, select the scene, export it with the
current shader pack, quit.

Useful when camera paths are written by a script (Flashback stores them in
`.minecraft/flashback/editor_states/<replay-uuid>.json`) and renders should run unattended.

## How it works

The mod does nothing unless `.minecraft/flashback/autorender.json` exists when the game starts. If it does,
the mod waits for the title screen, opens the replay through Flashback's own code, switches to the
requested scene, starts the same export Flashback's export window would start, writes
`.minecraft/flashback/autorender-result.json` and closes the game. Normal play is unaffected.

Before touching a replay's editor state the mod copies it to `.minecraft/flashback/autorender-backups/`,
and it restores the previously selected scene afterwards.

## Requirements

- Minecraft 26.3 with Fabric Loader 0.20+ and Flashback 0.43.6 (built against that version; other
  versions may need changes)
- Java 25 to build
- Optional: a shader pack via Iris; the export uses whatever the game renders

## Build and install

Flashback's licence does not allow redistributing its jar, so it is not in this repo. Point the build
at your own copy:

```sh
./gradlew build -Pflashback_jar=/path/to/Flashback-0.43.6-for-MC26.3.jar
# or put the jar in libs/ (default path in gradle.properties)
```

Copy `build/libs/flashback-autorender-0.1.0.jar` into the mods folder of the instance you record with.

## Rendering a scene

### With the driver script (LabyMod launcher, Flatpak)

`render.py` writes the request, starts the LabyMod instance without clicks, waits for the result and moves
the video out of the game folder:

```sh
export AUTORENDER_LABYMOD_INSTANCE=<your instance id>
python3 render.py <replay> <scene> [-o out.mp4] [--width 1920 --height 1080 --fps 60 --bitrate 20m] [--timeout 1800]
```

`<replay>` is a name in `.minecraft/flashback/replays/` (without `.zip`) or a path to a replay zip.
Without `-o` the video goes to `~/Downloads/<replay>-<scene>-<time>.mp4`. The script refuses to start
while Minecraft is already running.

The instance id is the folder name under `~/.var/app/net.labymod.LabyMod/config/LabyMod/instances/`.

### With any other launcher

Write the request yourself and start the game normally:

```json
{
  "replay": "/home/you/.minecraft/flashback/replays/myreplay.zip",
  "scene": "Scene 1",
  "output": "/home/you/.minecraft/flashback/exports/myreplay.mp4",
  "timeoutSeconds": 1800,
  "settings": { "width": 1920, "height": 1080, "framerate": 60, "bitrate": "20m" }
}
```

Save it as `.minecraft/flashback/autorender.json`. `settings` is optional; missing values fall back to
the last export settings in Flashback's config (`config/flashback/flashback.json`). Flashback does not
store the bitrate, so the default is 20m. The output path must be somewhere the game process can
write to (a sandboxed launcher may only see the game folder).

When the game has closed, `autorender-result.json` says whether the export succeeded, where the file is,
and which steps ran.

## Limitations

- Failure paths (missing scene, timeout) are implemented but barely tested.
- The LabyMod launcher window stays open after a render.
- Only one scene per game start.

## Licence

MIT, see [LICENSE](LICENSE). Flashback itself is not included and keeps its own licence.
