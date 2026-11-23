# Installing Splint

Splint is a Java agent for the Project Zomboid Build 42 dedicated server. It patches a short list of game methods in memory when the server starts and changes no game files. Workshop mods cannot install Java code, so it is installed once on the server machine.

Tested on the Linux dedicated server (steamcmd app 380870) with Build 42.22.2. The Windows server should work the same way but has not been tested. A hosting panel that cannot edit the server's JVM arguments cannot install Splint; ask the host to add the argument from step 3.

## 1. Download

From the [latest release](https://github.com/DevGuyRash/Splint/releases/latest), download `splint.jar` and `SHA256SUMS` into the same folder, then check the download:

```bash
sha256sum -c --ignore-missing SHA256SUMS
```

On Windows (PowerShell), compare the output with the `splint.jar` line in `SHA256SUMS`:

```powershell
(Get-FileHash splint.jar -Algorithm SHA256).Hash.ToLower()
```

## 2. Copy

Stop the server and copy `splint.jar` into the server's `java/` folder, next to `projectzomboid.jar`.

## 3. Add the JVM argument

Open `ProjectZomboid64.json` in the server's folder and add `-javaagent:java/splint.jar` as the first entry of `vmArgs`:

```json
"vmArgs": [
    "-javaagent:java/splint.jar",
    "-Djava.awt.headless=true",
    ...
]
```

If the server starts from a script that runs `java` directly instead, add `-javaagent:` followed by the path to `splint.jar` to that `java` command, before the main class.

## 4. Start and check

Start the server. The console shows `[Splint] INFO Splint <version> agent loaded` first, then `Splint <version> active: N of N patches applied` once the world is running. Then run this in the server console:

```
splint status
```

It should report `N of N patches applied, none missing`. A `MISSING` list means a game update changed those methods; Splint then runs them as vanilla instead of failing, and an updated Splint release is needed.

Splint logs only to the server console and to `Logs/<date>_Splint.txt` next to the game's own logs; players never see its output. In-game, `/splint` is limited to admins (the `ChangeAndReloadServerOptions` capability). Commands are listed in the [README](https://github.com/DevGuyRash/Splint#commands).

## Upgrade

Stop the server, replace `java/splint.jar` with the new one, start the server and run `splint status` again.

## Uninstall

Remove `-javaagent:java/splint.jar` from `vmArgs` (and the jar, if you like). Saves stay compatible both ways: Splint only changes which ID numbers new animals get, and keeps one small file, `splint/animal-ids.txt`, in the save folder.

## Options

JVM options go next to `-javaagent` in `vmArgs`:

- `-Dsplint.disable=animals` turns a module off; `-Dsplint.modules=animals` turns on only the listed ones. The core is always on.
- `-Dsplint.side=server|client` overrides side detection (normally not needed).
