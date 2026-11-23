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
- **Hutch, trailer, inventory:** they age only while loaded, with no catch-up. Hutch animals never change stage while inside (`IsoHutch.updateAnimalInside` has no `checkStages`). They grow as soon as they are released.
- **Wild or virtual animals** do not age while virtual.

So a server on "Very Fast" ages every animal 10×, everywhere. Nothing is extra for empty chunks.

### 9. Hutches, trailers and inventories (reproduced)

- **Hutch:** chicks past their growth age stay chicks while inside (`IsoHutch.updateAnimalInside` has no stage check). On release they all grow on the next frames, at the hutch's exit square. A hutch therefore concentrates growth events, all of them at one spot, which is exactly where a nearby ghost does the most damage.
- **Trailer:** juveniles in a livestock trailer grow in place and keep their IDs (`replaceGrownAnimalInTrailer`). The next stage is still constructed at the trailer animal's stored coordinates first, so the constructor's water and duplicate checks run there. Unloading a trailer constructs the animal at the trailer's "AnimalEntry" area, another spot that can be a water square.
- **Inventory:** see the open questions.

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
| 4, 5, 6 limbo | The update scheduler's per-object calls (preupdate, frameStep, update, postupdate) are each isolated for every moving object, as are animal sound and hutch updates: a failure is logged with location and players, and the object is frozen with back-off after 3 failures in a row instead of aborting the world update. The lab's damaged-animal case (`malformed-breed`) failed in postupdate, which an update-only guard would have missed. |
| 7 `remove animals` | `/splint animals kill` with combinable selections (cell, area, player, hutch, trailer, inventory, type, stage, sex, age, place, ghosts, frozen, duplicates), dry run by default. |

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

## Open questions

- The first ghost on the incident server is not identified. Water squares, a removed animal mod's type, and trailer unloading at the water's edge all produce one in vanilla. A log from before the first error, or `/splint animals census` on the affected cells with Splint installed, would show which one.
- Whether the rollback helped because it removed a ghost-producing situation from the save, or only because the restart cleared in-memory ghosts, cannot be told from the report. Ghosts left only on a square (finding 5) are never saved. Ghosts still in the update list (findings 3 and 4) are handed to the population save, where `IsoAnimal.save` dereferences their null data (code; not reproduced in the lab). A restart clears the in-memory ones until the situation that creates them recurs.
- A carried juvenile that reaches its growth age leaves the inventory: `grow` removes the inventory item, and the next stage is created at the animal's stored map position (lab `inventory-growth`). Where that position is and whether it is loaded decides where the grown animal ends up. This is the most concrete mechanism found for the "ghost chicken in my pocket" account, but it was not linked to the incident.
