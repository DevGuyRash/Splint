# Animal failure investigation

This records what was checked, how, and what was found, for the multiplayer "world in limbo" incident described in the incident report (2026-11-22). The report's own conclusions were treated as hypotheses. Each finding below is marked **reproduced** (shown on a real dedicated server in the lab), **code** (read in the installed game's bytecode, decompiled with Vineflower 1.11.1) or **open**.

## Environment

- Game: Project Zomboid 42.22.2 (`version=42.22.2 3d39148846`), the dedicated server installed with steamcmd as app 380870, buildid 26486687. Its `java/projectzomboid.jar` is byte-identical to the client's (SHA-256 `3c4a7c5f…d120d287`), so the decompiled code is exactly what the server runs.
- Lab: one dedicated server (`-nosteam`, loopback) plus one headless game client on a private Xvfb display, so chunks load around a real connected player. Default sandbox except zombies off. See `lab/`.
- Line numbers below refer to the decompiled 42.22.2 sources, not to the runtime line numbers in the incident's stack trace.

## Summary

The incident's symptoms are reproduced with one half-built animal. Vanilla can leave an `IsoAnimal` in the world whose constructor returned before initialising it: no type, no definition, no data, and ID -1. Nothing in the world update catches exceptions per object, so the first throw from such an animal abandons the rest of that frame's world update, every frame, for as long as the animal exists. That stops timed actions, server-side player stats, Lua ticks and animal sync for everyone, while the server process and console stay alive.

The exact stack in the report (`AnimalData.grow → new IsoAnimal → checkForChickenpocalypse → delete → … → AnimalVisual.getModelTest`, `textureSkinned`) is the same mechanism one step later: a growing animal next to a half-built animal. The duplicate check runs in the constructor while the new animal's ID is still -1, so it can only match another half-built animal. It then deletes the new animal, the deletion throws in model cleanup, and the new animal is left on its square as one more half-built animal. The growth is retried next frame, so the failure repeats and spreads.

The report's hypothesis about IDs is right that IDs are not unique: they are `Rand.Next(10000)`. But random collisions are not what produces the crash. They cause a different, silent bug: an animal that grows next to another animal with the same ID is deleted together with its next stage.

A ghost also breaks saving. While one exists, every save fails partway through the animal population (the console still prints "World saved") and the shutdown save never finishes, so a restart falls back to saves from before the ghost (finding 14). That is why the incident server needed a 20-hour rollback, and why the problem came back: the cause recurs. One cause fits the report's trailer clue and is reproduced: unloading a livestock trailer backed up to water builds the unloaded animals on a water square, where they come out half-built (finding 15). Dropping a carried animal from the seat of a vehicle whose door is over water does the same (finding 21). The other clues (logging in next to a trailer of chickens, carrying chickens or carcasses, butchering, Ovo Farms) were tested and ruled out (finding 16).

Status: Splint 0.4.0 keeps every reproduced failure from stopping the world update, a save or the rest of the map (finding 22), and loses no animals in any lab scenario. Which cause made the incident server's first ghost is not proven (see "Still open").

## Findings

### 1. Animal IDs are random 0–9999 with no uniqueness check (code, reproduced)

`IsoAnimal.init(AnimalBreed)` sets `this.animalId = Rand.Next(10000)` (IsoAnimal.java:1506). The field starts at -1 (line 138). Nothing checks the new value against existing animals. With n animals the expected number of colliding pairs is n(n−1)/2 / 10000. The lab's 460-animal farm held 10 duplicate pairs, against 10.6 expected.

The network ID (`onlineID`, `AnimalInstanceManager.allocateID`) is separate and unique among registered animals; it is not the ID the game uses to match mothers, babies, nest boxes, trailer lookups and the duplicate check.

### 2. A growing animal next to a same-ID animal disappears (code, reproduced)

`AnimalData.grow` builds the next stage, copies the parent's ID onto it, then calls `parent.checkForChickenpocalypse(newAnimal)` (AnimalData.java ~1402). If any other animal within the 8×8 window shares the ID, that check deletes the parent and returns true, and `grow` then deletes the new stage too. The animal is gone with no error. In the lab's mass-growth run, 460 juveniles grew and 459 animals remained.

### 3. Half-built animals ("ghosts") (code, reproduced)

Every `IsoAnimal(cell, x, y, z, type, …)` constructor first runs the `IsoGameCharacter` constructor. That constructor adds the object to the cell's update list (`cell.addMovingObject`) and to its square (`setMovingSquareNow`) whenever the coordinates are not 0,0,0 (IsoGameCharacter.java ~782, ~817). Only then does `IsoAnimal` decide whether to initialise. It returns early, leaving the object in the world uninitialised, when:

- the square is water (`checkForWater()`);
- the type has no definition (`adef == null`, e.g. a removed animal mod's type);
- `checkForChickenpocalypse(null)` matched, which, because the new animal's ID is still -1 at that point, only happens when another half-built animal is within 4 squares.

Reproduced: `addAnimal` of a hen on a Fallas Lake water square returned an animal with `id=-1 data=nil type=nil`, standing on the square.

Vanilla code paths that construct animals at arbitrary squares include growth (at the parent's position), unloading a trailer (`AnimalCommandPacket` RemoveAnimalFromTrailer, at the trailer's "AnimalEntry" area), dropping a carried animal, egg hatching, births, reanimation, and chunk/ranch/vermin spawns. None of them check the result.

### 4. One ghost stops the world update for everyone (reproduced)

`IngameState.updateInternal` wraps the whole world update in a single `try`. There is no per-object catch in `IsoCell.ProcessObjects` or `MovingObjectUpdateScheduler`. A throw from one animal skips every object after it, `IsoCell.updateInternal`'s tail, `AnimalController`, `onTick` (Lua `OnTick`), and `updateManagers` (`ActionManager`, `TransactionManager`). On a server the catch returns and the next frame does the same.

Lab, one water ghost, vanilla server:

- `NullPointerException: Cannot read field "turnDelta" because "this.adef" is null at IsoAnimal.update`, once per frame: 757 in the first 16 s, then about 10 per second (the incident report: 12,000 in 22 min ≈ 9.1/s).
- The server's Lua `OnTick` heartbeat stopped immediately.
- The connected player's server-side hunger and thirst stopped changing (0.0403 / 0.0343, unchanged over 10 s).
- After teleporting 3,500 squares away, the client had the new squares, but none of the 460 animals standing there were sent to it.
- The errors continued with no player anywhere near the ghost: it stays in the server's global update list.
- The console kept working.

This matches the report's "no players can take any actions… endurance and thirst/hunger stop changing… no timed actions work… everything is in limbo", and "even where no one is".

### 5. The report's stack: growth beside a ghost (reproduced: `grow-near-ghost`, `grow-near-invalid-ghost`)

When a juvenile within 4 squares of a ghost reaches its growth age, `grow` constructs the next stage. The constructor's duplicate check compares -1 with -1, matches the ghost, and calls `delete()` on the new, uninitialised animal. `delete` → `removeFromWorld` → `IsoGameCharacter.removeFromWorld` → `releaseRagdollController` → `getAnimationPlayer` → `ModelManager.getBodyModel` → `AnimalVisual.getModelTest` reads `AnimalDefinitions.getDef(null).textureSkinned` and throws. That is the report's exact frame sequence. `IsoMovingObject.removeFromWorld` had already taken the object out of the update list, but `delete` never reaches `removeFromSquare()`. So the failed new stage stays on the square as a ghost, the parent is unchanged, and the growth repeats next frame.

### 6. `getAttachedTree` on null data (reproduced: `unload-with-ghost`)

`IsoAnimal.unloaded()` calls `getData().getAttachedTree()` (IsoAnimal.java:1652). Chunk unload (`AnimalPopulationManager.removeChunkFromWorld`) calls it for every animal on the chunk's squares, ghosts included, giving exactly "Cannot invoke AnimalData.getAttachedTree() because IsoAnimal.getData() is null". In vanilla that exception is caught at chunk level after it has skipped the rest of that chunk's population bookkeeping. The square-only ghosts left by finding 5 are the ones that reach it. So the report's two signatures are consistent with one ghost population: `textureSkinned` while juveniles keep trying to grow, and `getAttachedTree` when their chunks unload. The lab's `unload-with-ghost` run produced exactly that pair, 723 of each, once per retried unload of the ghost's chunk.

### 7. Vanilla `remove animals` from the console does nothing (reproduced)

`RemoveCommand` only stores the issuing connection in `GameServer.removeAnimalsConnection`. The main loop runs the removal only when that connection is not null, and a console command has no connection. The console prints "Animals removed" and nothing happens: the lab's error rate was unchanged (100 per 10 s before and after). From in-game it removes only animals registered with `AnimalInstanceManager`, which ghosts never are.

### 8. "Animals age 10x faster in a chunk no one is in" (code: refuted as stated)

There is no code path where an unobserved animal ages faster. Aging speed is the sandbox option `AnimalAgeModifier`, applied the same way to loaded and unloaded animals (`AnimalData.getAgeGrowModifier`). The option labels and factors are Ultra Fast 90×, Very Fast **10×**, Fast 5×, Normal 1×. Slow and Very Slow are also 1×: they are not implemented. The default presets use Normal (Apocalypse, Survivor, Builder) or Fast (Outbreak).

What differs for unloaded animals is how time is applied:

- **Loaded, nobody near:** on a dedicated server every animal is updated every frame (the scheduler's distance throttling is client-only). Age advances by `modifier` days per in-game day.
- **Unloaded, inside a player's animal zone:** on reload, `DesignationZoneAnimal.doMeta` calls `updateStatsAway(hours)`, which adds `floor(hours/24) × modifier` days in one step. Several growth stages can then be passed on consecutive frames after the area loads. That is a burst of growth right when a player arrives, which fits "loading OvO Farms and leaving it" as a trigger for growth-related failures.
- **Hutch, trailer, inventory:** measured in finding 17. Carried animals do not age at all; trailer animals age while loaded, with no catch-up. Hutch animals never change stage while inside (`IsoHutch.updateAnimalInside` has no `checkStages`). They grow as soon as they are released.
- **Wild or virtual animals** do not age while virtual.

So a server on "Very Fast" ages every animal 10×, everywhere. Nothing is extra for empty chunks.

### 9. Hutches, trailers and inventories (reproduced)

- **Hutch:** chicks past their growth age stay chicks while inside (`IsoHutch.updateAnimalInside` has no stage check). On release they all grow on the next frames, at the hutch's exit square. A hutch therefore concentrates growth events, all of them at one spot, which is exactly where a nearby ghost does the most damage.
- **Trailer:** juveniles in a livestock trailer grow in place and keep their IDs (`replaceGrownAnimalInTrailer`). The next stage is still constructed at the trailer animal's stored coordinates first, so the constructor's water and duplicate checks run there. Unloading a trailer constructs the animal at the trailer's "AnimalEntry" area, another spot that can be a water square.
- **Inventory:** see finding 17.

### 10. Duplicate IDs also lose animals when a chunk unloads (code; lab `legacy-dup-reload`)

When a chunk unloads, its animals join virtual herds in the population worker. `AnimalManagerWorker.addAnimal` (AnimalManagerWorker.java ~197-219) removes any joining animal whose ID already exists in the herd, logging only at debug level ("Trying to add an animal with an existing IsoAnimal.animalID"). Two same-ID animals near each other therefore become one the next time nobody is around. This is a second silent loss path, independent of growth (finding 2).

### 11. A ghost in the population save kills the population worker (reproduced: vanilla `stress-5000` beside water)

When a mass spawn overlapped a river, 303 of the new animals came out half-built (finding 3), and vanilla's world update stopped. Later the animal population worker thread died: `Exception in thread "Thread-0" java.lang.NullPointerException: Cannot invoke "AnimalData.getBreed()" because "this.data" is null` in `IsoAnimal.save`, reached through `VirtualAnimal.save` → `AnimalChunk.save` → `AnimalCell.save` → `AnimalManagerWorker.save`. That is the signature of reference R17 in the incident report, there attributed to a heavily modded single-player save; here it happens unmodded on a dedicated server. With the worker thread gone, the server no longer saves or restores animal populations at all. Splint prevents the ghost (water), and removes ghosts before chunk unload, before virtualisation and before the population save.

### 12. How Splint answers each finding

| Finding | Splint change |
|---|---|
| 1 random IDs | `Rand.Next(10000)` in `IsoAnimal.init` is replaced in place by a per-world allocator (unique, monotonic, persisted in blocks). A primitive `int` → weak-reference hash index of live animals answers "who has this ID" in O(1). |
| 2, 10 duplicate loss | Duplicates are refused in `setAnimalID`, re-assigned when an animal enters the world, and fixed before its chunk unloads. Growth hands the parent's ID to its next stage explicitly; nothing is deleted over an ID. |
| 3, 11 ghosts | Animals created on water are moved to land. Ghosts found on update, at chunk unload, at save, or beside a growing animal are removed with a removal that cannot throw. The constructor's duplicate check no longer runs before the ID exists. |
| 22 stuck map cells | The server's per-cell map work is isolated: an object that cannot be removed is skipped, a chunk that cannot be unloaded is still saved, and a cell that fails does not keep other cells from updating and unloading (`core.isolate.MapIsolation`). `build.py --verify` unloads a real map cell whose 64 chunks all fail. |
| 4, 5, 6 limbo | The update scheduler's per-object calls (preupdate, frameStep, update, postupdate) are each isolated for every moving object, as are animal sound and hutch updates: a failure is logged with location and players, and the object is frozen with back-off after 3 failures in a row instead of aborting the world update. The lab's damaged-animal case (`malformed-breed`) failed in postupdate, which an update-only guard would have missed. |
| 7 `remove animals` | `/splint animals kill` with combinable selections (cell, area, player, hutch, trailer, inventory, type, stage, sex, age, place, ghosts, frozen, duplicates), dry run by default. |
| 14 saves fail | Ghosts never form or are removed first, so saves complete. If one still reaches a herd being saved, it is dropped before the herd is counted (`VirtualAnimal.save`), so one bad animal can no longer abort a save. `build.py --verify` checks this against the real save method. |
| 15 trailer at the water | The animal built at a water square is moved to the nearest land before it is initialised, so it comes out whole. |

### 13. Lab scenarios

Each scenario runs in a fresh session. Results: `vanilla` without Splint, `splint` with it.

Each row is one scenario from `lab/scenarios.py`, run on the steamcmd dedicated server with a headless client, first without and then with Splint (`lab/report.py` builds this table from the runs' `results.json`). "World update stopped" means the server's Lua tick heartbeat, which runs inside the world update, stopped. Stress rows count animals in the server's whole update list, with the player invincible (grown rams and boars attack, and a dead player would unload the area), on the stock 8 GB server heap; the vanilla and Splint stress runs were interleaved, because a shared machine's load changes from run to run.

| Scenario | Vanilla 42.22.2 | With Splint |
|---|---|---|
| `mass-growth` | no exceptions; world update running; 4 animals lost; duplicate-ID groups 15 → 11 | no exceptions; world update running; 0 animals lost; duplicate-ID groups 0 → 0 |
| `duplicate-ids` | no exceptions; 1 animals lost; duplicate-ID groups 0 → 0 | no exceptions; 0 animals lost; duplicate-ID groups 0 → 0 |
| `invalid-type` | 425× `turnDelta`; **world update stopped** (Lua ticks dead); player stats frozen | no exceptions; world update running |
| `water-seed` | 424× `turnDelta`; **world update stopped** (Lua ticks dead) | no exceptions; world update running |
| `grow-near-ghost` | 475× `textureSkinned`; **world update stopped** (Lua ticks dead); player stats frozen; 0 duplicate-ID groups | no exceptions; world update running; duplicate-ID groups 0 → 0 |
| `unload-with-ghost` | 1298× `turnDelta`, 723× `getAttachedTree`, 723× `textureSkinned`; **world update stopped** (Lua ticks dead); player stats frozen | no exceptions; world update running |
| `grow-near-invalid-ghost` | 479× `textureSkinned`; **world update stopped** (Lua ticks dead); player stats frozen; 0 duplicate-ID groups | no exceptions; world update running; duplicate-ID groups 0 → 0 |
| `inventory-growth` | no exceptions; world update running | no exceptions; world update running |
| `trailer-growth` | no exceptions; world update running | no exceptions; world update running |
| `legacy-dup-reload` | no exceptions; world update running; 0 animals lost; duplicate-ID groups 1 → 1 | no exceptions; world update running; 0 animals lost; duplicate-ID groups 1 → 0 |
| `hutch-growth` | no exceptions; world update running | no exceptions; world update running |
| `stress-2000` | no exceptions; world update running; 5 animals lost; ticks/s 10.07 empty, 5.6 loaded, 6.87 after growth; server CPU ms/tick 6.6 empty, 216.6 loaded, 149.8 after growth; duplicate-ID groups 154 → 146 | no exceptions; world update running; 0 animals lost; ticks/s 10.07 empty, 5.27 loaded, 8.0 after growth; server CPU ms/tick 4.5 empty, 237.9 loaded, 127.2 after growth; duplicate-ID groups 0 → 0 |
| `stress-5000` | no exceptions; world update running; 21 animals lost; ticks/s 10.13 empty, 1.4 loaded, 1.4 after growth; server CPU ms/tick 3.9 empty, 935.0 loaded, 759.6 after growth; duplicate-ID groups 886 → 840 | no exceptions; world update running; 0 animals lost; ticks/s 10.07 empty, 1.07 loaded, 1.53 after growth; server CPU ms/tick 3.0 empty, 957.7 loaded, 681.2 after growth; duplicate-ID groups 0 → 0 |
| `malformed-breed` | 525× `getName`; **world update stopped** (Lua ticks dead); 0 duplicate-ID groups | no exceptions; world update running; duplicate-ID groups 0 → 0 |
| `admin-commands` | not run | no exceptions; world update running; all command checks pass |

Speed, four interleaved runs on the final build (vanilla, Splint, vanilla, Splint), measured as the dedicated server's CPU milliseconds per world tick across all its threads, mean of two runs (individual runs in brackets). Lower is better. CPU time per tick is far less sensitive to other load on the machine than ticks per second, which varied by up to 50% between identical runs here.

| Animals | Vanilla | Splint |
|---|---|---|
| 2,000 loaded | 251 ms (286, 217) | 214 ms (190, 238) |
| 2,000 after growth | 176 ms (201, 150) | 133 ms (140, 127) |
| 5,000 loaded | 978 ms (1,022, 935) | 901 ms (844, 958) |
| 5,000 after growth | 670 ms (580, 760) | 634 ms (587, 681) |
| animals lost in growth | 5, 5 and 18, 21 | 0, 0 and 0, 0 |

Splint adds no measurable cost. Its means were lower in all four rows, but the runs overlap, so no speed-up is claimed.

Notes:

- Duplicate-ID loss in vanilla is random. Whether a growing animal has a same-ID neighbour within 4 squares varies, so the counts vary between runs: `mass-growth` lost 0, 1 and 4 animals in three vanilla runs. With Splint, no run has lost an animal.
- At 5,000 animals the server slows to about one tick per second with or without Splint; that is the game's own animal simulation.
- `legacy-dup-reload` did not trigger vanilla's herd-join drop (finding 10) in this run; the duplicate simply survived. With Splint it was repaired.
- `inventory-growth`, `trailer-growth` and `hutch-growth` behave the same with and without Splint; they document how growth works in each storage place (finding 9).

## Open questions, answered (2026-11-23)

The three open questions were taken back to the lab with new scenarios (`lab/scenarios.py`, the `OPEN_QUESTIONS` set). Each was run on vanilla and with Splint. Restarts are real: the server saves through its own `quit`, and a new session continues that save with the same player (`splintlab.py start --resume`). In-game time runs in the "time chamber" (one real minute per in-game day, `settimespeed 20`: about 7 in-game hours per real second, tick rate unchanged), and scenarios wait for in-game hours, not real seconds; `time-chamber` measured the speeds. Juveniles are set half a day short of their growth age, read from the game's own stage table, so the game's clock makes them grow.

### 14. Why the rollback helped: a ghost makes every save fail partway (reproduced: `ghost-save-restart`)

With a ghost in memory, vanilla cannot save:

- **`save` (what scheduled saves run):** the console prints "World saved" at once, then the save writes the map and the players and throws in the animal population save (`IsoAnimal.save`: "this.data" is null, through `AnimalPopulationManager.save` → `AnimalManagerWorker.save` → `VirtualAnimal.save`). "Saving finish" never comes. After a restart the player was where they had been, but 5 of the 6 hens placed before the save were gone.
- **`quit` (a restart):** the shutdown save hits the same exception in its own thread, which dies; the save never finishes and the server has to be killed. The world that loads next is whatever earlier saves left on disk.

The ghost itself is never written (the save fails on it), so the world that loads is free of it and runs normally. That is what the 20-hour rollback did: it went back to a save from before the ghost, losing everything since. Any restart does the same with the latest saves, losing what failed to save. The failure comes back when whatever made the ghost happens again. Stock servers save only when areas unload (`SaveWorldEveryMinutes=0`), so failing saves can go unnoticed for a long time.

With Splint the ghost never forms (or is removed first); `save` and `quit` complete and the restarted world has everything. As a last line of defence, a ghost that still reaches a herd being saved is dropped before the herd is counted, so one bad animal can no longer abort a save (finding 12; checked against the real save method by `build.py --verify`).

### 15. A trailer unloaded at the water's edge makes ghosts (reproduced: `trailer-unload-water`)

`BaseVehicle.removeAnimalFromTrailer` builds the animal anew (`new IsoAnimal(...)`) at the centre of the trailer's `AnimalEntry` area, about one and a half tiles behind it, then copies the old animal into it; `ISRemoveAnimalFromTrailer` adds it to the world. When that spot is a water square, the constructor stops early (finding 3) and the copy restores the ID and data but not the type. In the lab, two hens unloaded from a trailer backed up to Fallas Lake came out with `type=nil`; the world update stopped at once (424 `turnDelta` errors, Lua ticks dead, player stats frozen).

This is the most concrete source found for the incident's first ghost: the report ties the failure to a player with a trailer full of chickens. It is not proven to be the one on that server. With Splint the same unload moves the hens to the nearest land; they come out whole and nothing fails.

### 16. Ruled out by the lab

None of these produced a ghost, an exception or a stopped world update, on vanilla or with Splint:

| Report clue | Scenario | Result |
|---|---|---|
| A player logs back in next to a trailer full of chickens | `trailer-relog` | All 8 animals intact after save and relog; the chicks grow in the trailer later |
| "I butchered some chickens before I logged" | `weapon-kill-butcher` | One hen killed with a weapon from each weapon category the game defines (9, read from the item scripts: ranged, axe, blunt, improvised, long blade, small blade, small blunt, spear, unarmed), 8 carcasses butchered the game's way (626 items), one carried; save, relog and two in-game days are clean |
| "Possibly a ghost chicken in my pocket" (carcasses) | `carcass-relog` | Three carried chicken carcasses survive save, relog and two in-game days |
| "Loading OvO Farms and leaving it" | `ovo-farms` | Ovo Farms (1245 Payne Road, Echo Creek, three chicken ranch zones around 3960,10805) visited, left for 2.5 in-game days twice, chicks growing while it is unloaded: no errors, no ghosts. Animals of a reloaded farm come back from the population worker some time after the area loads, so a count right after arriving can be short; Splint's run found all 7 IDs again on the next visit |

### 17. Carried, trailered and hutched animals (reproduced: `pocket-far-growth`, `pocket-relog`, `trailer-relog`, `hutch-pocket`, `aging-rates`)

- **Carried animals do not age.** A chick carried for 65 in-game hours stayed at 125 days (its hours survived moved by 4), so it never reached its growth age. Finding 8's code reading ("age only while loaded") was wrong for the inventory.
- **A carried animal grows only if it is already past its growth age** (in the original `inventory-growth`, set by the lab). `AnimalData.grow` then builds the next stage at the coordinates the animal carries (where it was last on the ground or in a hutch), removes the item and adds the grown animal to the world there, not next to the player.
- **After a relog, a carried animal is inert.** It keeps its stored coordinates but loses its container link (`getContainer()` is null) and does not grow even when set past its growth age. With a null container, `grow` would delete the old animal without removing its item, but carried animals are not updated after a relog, so the lab could not reach that path.
- **Trailer animals age while loaded** (125 to 128 days in 66 in-game hours) and grow in place. In the chamber only one of six grew: the trailer updates its animals on a real-time limiter, so a test that passes days in seconds starves it. That is a limit of the chamber, not a game bug.
- **Hutch animals** stay chicks past their growth age inside the hutch (finding 9) and keep the hutch tiles as their coordinates, across a restart. Taking one out after a restart, as `ISHutchGrabAnimal` does, puts it in the pocket cleanly: nothing appears in the world and nothing fails, on vanilla and with Splint.
- **Aging rates by place** (`aging-rates`, days of age per in-game day, Normal age speed):

  | Place | Slow clock (1 real minute per day) | Time chamber |
  |---|---|---|
  | On the map | 0.99 | 0.82 |
  | Livestock trailer | 0.99 | 0.82 |
  | Hutch | 0.99 | 0.82 |
  | Carried | 0.5 | 0.0 |

  Nothing ages faster anywhere, which confirms finding 8: aging follows the sandbox setting. Carried animals barely age (ages are whole days, so 0.5 is coarse). The chamber runs every place about 18% slow, a limit of the chamber; rates are measured at the slow clock.

### 18. `grow` deletes an animal standing on water (code)

After building the next stage, `AnimalData.grow` calls `parent.checkForWater()` and deletes both when it returns true. An animal that is on a water square when it grows disappears without a log line. Splint moves it to land instead. Animals in a trailer, an inventory or a hutch are not affected in practice: the game clears their square when they are stored, so the check finds no water (`stored-on-water-growth`: trailer chicks with a water position grew normally on vanilla and with Splint). Splint also never deletes a stored animal for it, in case one keeps a square.

### 19. The dedicated server does not update zombies (code, reproduced: `server-zombie-update`)

An earlier version of the README said the server updates zombies on a separate path that Splint does not wrap. That was wrong. `MovingObjectUpdateScheduler.startFrame` takes every `IsoZombie` aside when `GameServer.server` is set: it only adds it to its target player's chasing count (and calls `updateForServerGui` when the server runs with its GUI), then moves on without adding it to an update bucket. Connected clients simulate zombies; the server relays them. In the lab a zombie in the server's update list received no `OnZombieUpdate` events, and clearing its legs sprite, which `IsoZombie.updateInternal` dereferences, threw nothing, on vanilla and with Splint. A zombie therefore cannot stop the server's world update the way a ghost animal does, and there is no server-side zombie update for Splint to guard. Neither the incident report nor the reports it cites involve zombies.

### 20. A pasture built over water does not cause it (reproduced: `pasture-pond`)

Admins asked whether players building a pasture over water could be the cause. No game code builds pasture animals at arbitrary pasture squares (every place that constructs an animal at given coordinates was listed from the bytecode; pastures are not among them), so the question is whether pasture animals end up standing on water, where growth would build their next stage. A fenced pasture was made the way the game's pasture tool does (`DesignationZoneAnimal.new` and `createSurroundingFence`) over the Fallas Lake shore, 284 of its 806 squares open water, with 120 chicks, 20 lambs and 20 piglets growing over the following week and 30 hens. A server ticker sampled every animal's square while five in-game days passed: no animal ever stood on a water square, juveniles grew normally, and there were no ghosts or exceptions, on vanilla and with Splint (Splint's water relocations and ghost counters stayed at zero).

When the player then left for three in-game days and came back, the juveniles were dead and the adults alive: every missing ID was matched by a corpse in the pasture (102 chicks, 20 lambs, 17 piglets), none anywhere else. The pasture had no feeder or trough. The same juveniles had lived through five loaded in-game days in that pasture, on its grass with the lake inside the fence, but the catch-up applied when the area loads again (`IsoAnimal.updateStatsAway` → `AnimalData.hourGrow` → `updateHungerAndThirst`, `updateHealth`) raises hunger and thirst hour by hour, and evidently does not count the grass and the lake. Predators were not involved: the away predator roll only runs with `AnimalMetaPredator`, which was off. That is the game's animal care working as coded, not this failure; it does mean a pasture that keeps animals alive while players are around can lose its young while nobody is there, unless it has feeders and troughs.

### 21. The player's own account, tested (reproduced: `drop-on-water`; ruled out: `trailer-unload-dead`, `trailer-egg-compartment`, `pasture-pond-unfenced`)

The player whose farm was at the centre of the incident described it later: an abandoned livestock trailer with about six dead hens and six chicks; chicks that "hatched into the egg compartment"; one chick that "said it was alive, but it plopped onto the ground dead"; one that gave "a blood animation without any body to butcher"; the rest carried in the pocket and dropped at a farm built over water, which the server lost track of. Every time they joined before the rollback the server "crashed", and they and one other player were put back on the same spot at every login, across resets. Each step was run on vanilla and with Splint:

| Step | Scenario | Vanilla | Splint |
|---|---|---|---|
| Drop a carried chick on foot at the water's edge | `drop-on-water` | Lands on the player's land square. Water squares are never a drop target (`ISInventoryTransferAction:canDropOnFloor`), even with all seven land squares around the player full | same |
| Drop it from the seat of a car parked at the water, door over water | `drop-on-water` | **Ghost** (`type=nil`), 393 `turnDelta`, world update stopped, player stats frozen | Whole chick, no exceptions, world update running |
| Unload dead, dying and healthy hens and chicks from a trailer on land, grab one and drop it | `trailer-unload-dead` | Dead ones become corpses at the trailer's entry, living ones come out whole; no ghosts, no exceptions | same |
| Hatch two eggs in the trailer's egg compartment, leave it 5 in-game days loaded and 5 unloaded, take the chicks out to the ground and through the pocket | `trailer-egg-compartment` | Chicks hatch as Animal items in the compartment and stay at age 1, hunger 0, thirst 0 throughout; both come out healthy | same |
| The farm over water had no fence | `pasture-pond-unfenced` | As finding 20: no animal ever on a water square, no ghosts; the young starve in the catch-up after three days away (every missing ID matched by a corpse) | not run |

What this adds:

- **Dropping from a vehicle seat is a second reproduced way to make a ghost.** The drop puts the item on the door's square (`ISDropVehicleItemAction`, `IsoGridSquare.AddWorldInventoryItem`), and for an Animal item the game builds a new `IsoAnimal` there and copies the carried one into it, the same pattern as trailer unloading (finding 15), with no water check. Splint's constructor guard moves the new animal to land, but `AddWorldInventoryItem` then sets it back on the drop square, so it stands whole on the water square; it is initialised, nothing throws, and at growth Splint moves it to land instead of letting `grow` delete it (finding 18).
- **Egg-compartment chicks are frozen in time.** `Food.checkEggHatch` builds a chick hatching in a trailer container at the trailer's square, then removes it from the world and wraps it in an Animal item in that container. Like a carried animal (finding 17) it neither ages nor gets hungry or thirsty, loaded or not, which explains live chicks in a trailer abandoned long enough for the hens to die. Taking them out does not kill them: no catch-up runs when they enter the world.
- **Repeat "crashes" and being put back on the same spot are only partly explained.** A ghost in the update list stops the world update (finding 4), and the server then stops moving its copy of the players, so the position it saves is where they stood. A ghost that is only on its square leaves holes in the server's map around it, and a player in a hole is frozen on the server while others are not (finding 22), which fits "everyone was stuck in our chunk, just me and one other person". Losing every live chicken but keeping one corpse fits failed saves (finding 14): live animals are written by the population save that the ghost breaks, while corpses are saved with the map chunk. What brought the ghost back after each restart is not known: the ghost itself is never saved (finding 14). Being put back at login was not measured in the lab.
- Not reproduced: the chick that came out dead and the death with no body. One lab trailer that the world never took in (a debug spawn on one spot near the lab's spawn point; the scenario now checks for this) put unloaded animals at its bare script offset near 0,0 with no square, and dead ones' corpses nowhere, which looks like both; but a trailer a player stands next to is always in the world, so that is a lab artifact, not an explanation.

### 22. A ghost's map area never unloads, and punches holes in the server's map (reproduced: `presence-cycle`)

The player reported, after the rollback, that with an admin watching the console "the second I joined the errors stopped. Then when I left, the errors exploded and server crashed", and that an earlier session left the server slower and slower until its reset. The scenario leaves a ghost of the kind finding 5 produces (on its square, not in the update list) at Fallas Lake, and has the player leave and return twice, travelling on in between; moving by teleport stands in for other players staying online. `presence-cycle-control` is the same trip without a ghost. The third column is Splint with the animals module switched off (`SPLINT_SERVER_JVM=-Dsplint.disable=animals`), so that the ghost is still there when its area unloads and only the isolation below is tested.

| Measured | Vanilla, no ghost | Vanilla, ghost | Splint core only, ghost |
|---|---|---|---|
| Errors while the player is there, before leaving | none | none | none |
| Errors after leaving (35 s) | none | 353 pairs of `getAttachedTree` and `textureSkinned`, one pair per tick; 612 more while travelling | one of each, then none |
| Errors after returning | none | one more pair, then none | none |
| Chunks of the ghost's 64-chunk map cell in memory, away / back | 0 / 64 | 15 / **15** | 0 / 64 |
| Player standing in the part that is gone | normal | no square under the player on the server; hunger and thirst do not move | normal |
| Axes in four chunks of the cell, after returning | all there | the one in the missing part has no square | all there |
| Map cells in memory after four further stops (heap) | 9 (1364 MB) | **50** (1899 MB) | 9 (1366 MB) |
| Server ticks per second | 10 | 10 | 10 |

The vanilla chunk counts were the same in an earlier run of the scenario. The control's player died of an unrecorded cause late in its run, after the phases above.

The code agrees. `ServerMap.postupdate` has one `try` around its loop over the loaded cells. When the ghost's cell fails to unload, the loop ends there for that tick: every cell after it in the list is neither updated nor unloaded, and since the failed cell is only taken off the list after a successful `Unload`, the same happens every tick. `ServerCell.Unload` hands each chunk to the save queue and then drops it, so the chunks before the ghost's are already gone (49 of 64 here) when it throws. If a player comes back, `loadOrKeepRelevent` finds the cell still present and only marks it needed, so the dropped chunks are not loaded again until the cell has really unloaded. The dropped chunks were queued for saving, so nothing in them is expected to be lost from disk; that was not tested.

What this explains and what it does not:

- "Errors stopped when I joined, exploded when I left" is reproduced. The errors depend on whether anyone is near the ghost, not on which player it is.
- The map cells that pile up (9 to 50 over a short trip) are a steady memory cost that only a player returning to the ghost, or a restart, clears. That fits "slower and slower until the reset", but tick rate did not fall in the lab with one player, so the slowdown itself is not reproduced.
- This kind of ghost does not stop the world update. Finding 6's `unload-with-ghost` run also stopped it because its ghost was still in the update list.
- The server crash on leaving is not reproduced.

With Splint the failure costs only the object: its removal is contained, the rest of its chunk unloads, the chunk is saved, and the loop goes on. In the core-only run vanilla's own handler still logged the one `getAttachedTree` and skipped the rest of that chunk's population and pathfinding bookkeeping; the animals module removes the ghost first, so with both modules on the run logged no errors at all. In that full run the ghost was already gone before the area unloaded (Splint's removal completes), so it does not test the unload guard; the core-only run does.

A cell that still holds chunks after failing is not dropped: the failure goes on to vanilla's handler, which keeps the cell and retries, because dropping it would lose chunks that were never saved. `preupdate` already isolates its own unload per cell in vanilla.

### Still open

- Which source made the incident server's first ghost. Findings 15 and 21 (a trailer unloaded, or a carried animal dropped from a vehicle seat, over water) fit the report and the player's account best; the player's account also has a chick that came out of the trailer dead and a death with no body, which the lab has not reproduced. With Splint installed, every removed ghost is logged with its place, coordinates, map cell and nearby players, and `/splint problems` lists them, so a recurrence would name it.
- What brought the failure back at each login before the rollback, when a ghost is never saved (finding 14), and what the server crash was. The console log of the recurrence would show which errors came first.
- How the original `getAttachedTree` reports relate in time to the `textureSkinned` ones on that server; both come from the same ghosts in the lab (findings 5 and 6).
