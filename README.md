# Splint

Splint is a server-side fix, optimisation and admin-tools framework for Project Zomboid Build 42, built as a small core with feature modules on top (in the spirit of LuaBuddy and ZombieBuddy). The core provides patching, logging, an incident history, admin commands and module switches. Each module adds fixes for one area.

The first module, `animals`, targets the animal failure that leaves a multiplayer world "in limbo": players cannot act, timed actions stop, hunger and thirst stop changing, chunks stop streaming, and animals and vehicles vanish from view while the console fills with `textureSkinned` / `getAttachedTree` / `turnDelta` null-pointer errors about ten times a second.

What it does today:

- **Unique animal IDs, generated correctly.** Vanilla's only source of new animal IDs is `this.animalId = Rand.Next(10000)` in `IsoAnimal.init`: a random number from 0 to 9999 with no uniqueness check. A 460-animal test farm held 8 to 15 duplicate pairs; 5,000 animals held about 900. Splint replaces that expression with a per-world allocator: IDs start at 10000, only increase, and are persisted in the save in reserved blocks, so restarts and crashes never reuse one.
- **O(1) duplicate checks.** Vanilla's duplicate check scans the 8×8 squares around every newly built animal, allocating a list per square, and cannot see a duplicate 5 squares away. Splint keeps an ID index, a primitive `int`-keyed open-addressing hash table with weak values, so every check is one lookup across the whole server. Duplicates are refused when set, repaired when an animal from an old save enters the world, and fixed before a chunk unloads (vanilla's population worker silently drops an animal whose ID repeats in the herd it joins).
- **No half-built animals.** Vanilla can leave an animal with no type, data or ID in the world: when it is created on a water square, or with an unknown type, or by its own duplicate check. One such animal throws on every frame and stops the whole world update, and makes every save fail partway (the console still says "World saved"), which is why only a rollback brought the incident server back. Unloading a livestock trailer backed up to water, or dropping a carried animal from a car seat with the door over water, are ways vanilla makes them. Splint moves animals created on water to the nearest land, removes ghosts it finds, makes animal removal always complete, and drops any half-built animal that still reaches the animal save, so one bad animal can no longer break a save.
- **Failure isolation.** The update scheduler's per-object calls (preupdate, frameStep, update, postupdate) run one object at a time: an exception from an animal, a vehicle or a player is caught, logged with its location and the players nearby, and the world keeps running. Animal sound and hutch updates, and animals carried in trailers and inventories, are isolated too. (The dedicated server does not run zombie updates at all: connected clients simulate zombies, and the server's scheduler skips them, so there is no server-side zombie update to guard.) An animal that fails on 3 frames in a row is frozen in place and retried with back-off; players and vehicles are never frozen, because the server must keep tracking them. `/splint frozen` lists and releases frozen objects. The server's map cells are isolated the same way: vanilla lets one object that cannot be removed stop every later map cell from updating and unloading, tick after tick, and leaves holes in the map around it. With Splint the object is skipped, its chunk is still saved, and the other cells carry on.
- **No silent animal loss.** When an animal grows next to another animal with the same ID, vanilla deletes both the animal and its next stage, without a word in the log. In the lab, vanilla lost 5 to 11 of 2,000 and 18 to 21 of 5,000 growing animals this way; with Splint, none.
- **Logging for admins, not players.** Every problem is written with the animal's type, breed, ID, coordinates, map cell, `apop_X_Y.bin` file, chunk, hutch or trailer, age, the nearest player and every player in that map cell. It goes to the server console/stdout and to `Logs/<date>_Splint.txt` next to the game's own logs, with repeats rate limited. Nothing is ever sent to players; command replies go only to the admin who ran them.
- **Incident history.** `/splint problems` lists what Splint handled since start, the map cells where it happened most, who is in those cells right now, and the latest incidents, so on a 100+ player server you can find the place and the people without guessing.
- **Admin commands with combinable filters.** `/splint ...` works from the server console, RCON and in-game chat (admins). Every animal command takes the same selection terms, so you can say "chicks in map cell 42,36, but not the ones in hutches" in one command. Destructive commands are dry runs until you add `confirm`, and every removal is logged with the admin's name.

The full investigation, with the reproduced failures, is in [docs/investigation.md](docs/investigation.md).

## Install on a server

Download `splint.jar` from the [latest release](https://github.com/DevGuyRash/Splint/releases/latest), copy it into the server's `java/` folder and add `-javaagent:java/splint.jar` to `vmArgs` in `ProjectZomboid64.json`. [INSTALL.md](INSTALL.md) has the full steps: checking the download, other start scripts, how to confirm it works, upgrading and uninstalling. The same file is attached to every release.

## Commands

From the console or RCON, drop the leading slash. In game, `/splint` needs the `ChangeAndReloadServerOptions` capability, which the default roles give to admins but not to moderators, because these commands remove animals and show every player's position. Replies go only to whoever ran the command.

```
/splint status                       patch health, ID counter, counters since start
/splint modules                      loaded modules
/splint problems [N]                 what Splint handled: by kind, worst map cells (with who is there now), latest N
/splint players [AREA]               online players: position, map cell, animals around them
/splint frozen [release] [confirm]   any objects frozen after repeated update errors
/splint animals census [SELECTION]   counts by map cell, place, type and stage; babies, ghosts, duplicates, frozen
/splint animals list [SELECTION]     individual animals: ID, type, stage, place, coordinates
/splint animals find ID              where the animal with this ID is
/splint animals kill SELECTION [confirm]       remove animals
/splint animals fixids [SELECTION] [confirm]   give duplicate IDs fresh unique ones
/splint animals frozen [release|kill] [confirm]
```

A SELECTION is any combination of these terms, in any order:

| Term | Meaning |
|---|---|
| `cell CX CY` | map cell `floor(x/256), floor(y/256)`, the same numbers as `apop_CX_CY.bin` |
| `near X Y R`, `me R`, `player NAME [R]` | squares around a point, yourself, or an online player (default 50) |
| `hutch X Y Z`, `trailer VEHICLE_ID`, `inventory NAME` | one hutch, one trailer, one player's carried animals |
| `type T[,T...]` | animal type (`hen`, `chick`, `cow`...) or group (`chicken`, `pig`, `sheep`...) |
| `stage baby` / `stage adult` | babies are animals that will still grow (chick, piglet, lamb...) |
| `sex male`, `sex female`, `age MIN-MAX` | age in days; `age 120-` and `age -7` work too |
| `in PLACES`, `except PLACES` | places are `world`, `hutch`, `trailer`, `inventory` |
| `ghosts`, `frozen`, `dupes` | only uninitialised, frozen, or duplicate-ID animals |
| `id N[,N...]`, `limit N` | specific animals; act on at most N |

Several where-terms (`cell`, `near`, `hutch`...) add up; every filter must match. `kill` touches the map, hutches and trailers by default, never a player's pockets unless you name `inventory NAME` or `in inventory`. Commands see what the server has in memory: everything in loaded areas, hutches, trailers and online players' inventories.

Examples:

```
splint problems
splint animals census cell 42 36
splint animals kill cell 42 36 type chicken stage baby except hutch
splint animals kill cell 42 36 in hutch confirm
splint animals list player Bob 40 type cow sex female
splint animals kill near 10850 9420 30 ghosts confirm
```

## Configuration

All modules are on by default. The JVM options that turn modules on or off are in [INSTALL.md](INSTALL.md#options).

`splint modules` lists what is loaded, and `splint status` shows patch health (`N of N patches applied, none missing`), the ID counter and every module's counters.

## Performance

Speed is a design rule, not an afterthought. On the normal path, the per-object, per-tick hooks do a few plain field reads (the circuit breaker's state is a field Splint adds to the object), with no allocation, no lock and no hash lookup. ID checks are O(1) hash lookups with primitive keys. Expensive work (finding players, building log lines, recording incidents) only happens when something went wrong or an admin runs a command, and repeated problems are rate limited before any of it is done.

Measured on the lab server in four interleaved runs (vanilla, Splint, vanilla, Splint), as server CPU per world tick: with 2,000 animals, 251 ms vanilla and 214 ms Splint; with 5,000, 978 ms and 901 ms. Splint adds no measurable cost (the runs overlap, so no speed-up is claimed). The slowdown with thousands of animals is the game's own animal simulation. Vanilla lost 5 of 2,000 and about 20 of 5,000 animals to duplicate IDs during growth in those runs; Splint lost none.

## Build

Needs a JDK that can target Java 25 and an installed game or dedicated server for the compile classpath.

```bash
python3 build.py --test --verify
```

This runs the unit tests, writes `dist/splint.jar`, applies every patch to the installed game's bytecode under the JDK verifier, checks that every hook has the exact signature its game method calls, and boots the game's own JRE with the agent to confirm everything applies. After a game update, a patch whose method changed is reported as not applied; the server then runs that method unpatched instead of failing.

## Test lab (repository only, not shipped)

`lab/` reproduces the failures on a disposable loopback multiplayer server: the steamcmd dedicated server (app 380870) plus a headless game client, so chunks load around a real connected player. It is development tooling; `dist/splint.jar` contains none of it. See [AGENTS.md](AGENTS.md).

Run every scenario, without and with Splint:

```bash
python3 lab/scenarios.py run .lab/runs/vanilla
```

```bash
python3 lab/scenarios.py run .lab/runs/splint --agent dist/splint.jar
```

Set up one scenario and leave the server running to inspect it by hand:

```bash
python3 lab/scenarios.py repro grow-near-ghost .lab/runs/repro-1
```

Scenarios include the report's exact stack (`grow-near-ghost`, `grow-near-invalid-ghost`), the `getAttachedTree` + `textureSkinned` pair on chunk unload (`unload-with-ghost`), duplicate-ID animal loss, hutch/trailer/inventory growth, a damaged animal (`malformed-breed`), the admin commands with asserted results, and 2,000- and 5,000-animal stress tests with tick-rate measurement. The open-question scenarios restart the server on its own save (`ghost-save-restart`, `trailer-relog`, `pocket-relog`, `carcass-relog`, `hutch-pocket`, `weapon-kill-butcher`), unload a trailer at the water's edge (`trailer-unload-water`), drop carried animals on foot and from a car seat at the water (`drop-on-water`), unload dead and living animals from a trailer (`trailer-unload-dead`), hatch eggs in a trailer's egg compartment (`trailer-egg-compartment`), build pastures over water with and without a fence (`pasture-pond`, `pasture-pond-unfenced`), leave and return to a ghost's area (`presence-cycle`, with `presence-cycle-control` as the same trip without one), and visit Ovo Farms (`ovo-farms`). In-game time runs in a "time chamber" of about 7 in-game hours per real second, so days pass in seconds.

## Scope and limits

- Install it on the dedicated server. On a multiplayer client the ID work stays off (the server owns animal IDs), and the safety fixes are harmless there, but nothing needs it client-side today. Single player and co-op hosts get the same fixes as a server; they are tested less.
- Splint does not change animal behaviour, growth rates or sandbox settings.
- Commands see what the server has in memory. Animals in areas nobody has loaded are not in memory; their duplicate IDs are fixed when they load or unload next.
- A save written before Splint can contain duplicate IDs. They are repaired when those animals enter the world or right before their chunk unloads, which is before vanilla's population worker would drop one of a pair (`AnimalManagerWorker.addAnimal` discards an animal whose ID already exists in the herd it joins).
- Splint never edits `apop` files or other save data directly.

## License

MIT, see [LICENSE](LICENSE). Splint contains no game code; it is built against your own installed copy of Project Zomboid.
