# Splint

Splint is a server-side fix and admin-tools patch for Project Zomboid Build 42 dedicated servers. Version 0.1 targets the animal failure that leaves a multiplayer world "in limbo": players cannot act, timed actions stop, hunger and thirst stop changing, chunks stop streaming, and animals and vehicles vanish from view while the console fills with `textureSkinned` / `getAttachedTree` / `turnDelta` null-pointer errors about ten times a second. It is meant to grow into a general fixes and quality-of-life patch.

What it does today:

- **Unique animal IDs.** Vanilla gives each new animal `Rand.Next(10000)`, a random number from 0 to 9999 with no uniqueness check. A 460-animal test farm held 10 duplicate pairs. Splint issues IDs from a per-world counter that starts at 10000 and only increases, persisted in the save so restarts and crashes never reuse one.
- **No half-built animals.** Vanilla can leave an animal with no type, data or ID in the world: when it is created on a water square, or with an unknown type, or by its own duplicate check. One such animal throws on every frame and stops the whole world update. Splint moves animals created on water to the nearest land, removes ghosts it finds, and makes animal removal always complete.
- **Failure isolation.** An exception in one animal's update, sound update or hutch update is caught, logged with its location, and contained. The rest of the world keeps running. An animal that keeps failing is frozen in place and retried with back-off; admins can inspect, release or remove it.
- **No silent animal loss.** When an animal grows next to another animal with the same ID, vanilla deletes both the animal and its next stage. Splint keeps both and gives the newcomer a fresh ID.
- **Logging.** Every problem is written with the animal's type, breed, ID, coordinates, map cell, `apop_X_Y.bin` file, chunk, hutch or trailer, age and nearest player. It goes to the console and to `Logs/*_Splint.txt`, with repeats rate limited.
- **Admin commands.** `/splint ...` works from the server console, RCON and in-game chat (admins). Destructive commands are dry runs until you add `confirm`.

The full investigation, with the reproduced failures, is in [docs/investigation.md](docs/investigation.md).

## Install on a server

Splint is a Java agent. It rewrites a short list of game methods in memory when the server starts and changes no game files. Workshop mods cannot install Java code, so it is installed once on the server machine.

1. Copy `splint.jar` into the server's `java/` folder (next to `projectzomboid.jar`).
2. Add `-javaagent:java/splint.jar` to the `vmArgs` list in `ProjectZomboid64.json` (tested on the Linux dedicated server; the Windows server reads the same file, but has not been tested).

   ```json
   "vmArgs": [
       "-javaagent:java/splint.jar",
       "-Djava.awt.headless=true",
       ...
   ]
   ```

   If you start the server with your own `java` command line, add the same argument before the main class.
3. Start the server. The console shows `[Splint] INFO Splint 0.1.0 agent loaded` first, then `Splint 0.1.0 active: N methods patched` once the world is running. Run `splint status` in the console: it should say `15 methods patched, none missing`. A `MISSING` list means a game update changed those methods; Splint then leaves them unpatched rather than failing.

To uninstall, remove the `-javaagent` argument. Saves stay compatible both ways: Splint only changes which ID numbers new animals get. It keeps one small file, `splint/animal-ids.txt`, in the save folder.

On a hosting panel that cannot edit `ProjectZomboid64.json` or the JVM arguments, Splint cannot be installed; ask the host to add the JVM argument.

## Commands

```
/splint status                                  patch health, ID counter, counters since start
/splint animals census [AREA]                   animals by map cell and type, ghosts, duplicate IDs, frozen
/splint animals list AREA [TYPE]                individual animals with ID, place and coordinates
/splint animals find ID                         where the animal with this ID is
/splint animals kill AREA [TYPE] [confirm]      remove animals
/splint animals fixids [confirm]                give every duplicate animal ID a fresh unique one
/splint animals frozen [release|kill] [confirm] animals frozen after repeated errors
```

`AREA` is one of `cell CX CY`, `near X Y R`, `me R`, `hutch X Y Z`, `hutches cell CX CY`, `trailer VEHICLE_ID`, `trailers near X Y R`, `inventory USERNAME`, `ghosts`, `id ID` or `all`. A map cell is `floor(x/256), floor(y/256)`, the same numbers as the `apop_CX_CY.bin` population file. `TYPE` is an animal type (`hen`, `chick`, `cow`...), a group (`chicken`, `pig`...) or `ghost`. From the console use the command without the leading slash. Commands see animals the server has in memory: everything in loaded areas, hutches, trailers and online players' inventories. Animals in areas nobody has loaded are not in memory.

Example: an error names `cell=42,36`. Run `splint animals census cell 42 36` to see what is there, `splint animals kill hutches cell 42 36` to check what removing hutch animals would do, and repeat it with `confirm`.

## Build

Needs a JDK that can target Java 25 and an installed game or dedicated server for the compile classpath.

```bash
python3 build.py --verify
```

This writes `dist/splint.jar` and checks every patch against the installed game's bytecode. After a game update, a patch whose method changed is reported as not applied; the server then runs that method unpatched instead of failing.

## Test lab

`lab/` reproduces the failures on a disposable loopback multiplayer server: the steamcmd dedicated server (app 380870) plus a headless game client, so chunks load around a real connected player. See [AGENTS.md](AGENTS.md).

```bash
python3 lab/scenarios.py run .lab/runs/vanilla
```

```bash
python3 lab/scenarios.py run .lab/runs/splint --agent dist/splint.jar
```

## Scope and limits

- Server side only. Clients need nothing. Single-player and co-op hosts are not supported targets yet.
- Splint does not change animal behaviour, growth rates or sandbox settings.
- Splint cannot repair animals that were never loaded, and it does not edit `apop` files.
