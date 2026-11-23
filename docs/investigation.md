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

### 11. How Splint answers each finding

| Finding | Splint change |
|---|---|
| 1 random IDs | `Rand.Next(10000)` in `IsoAnimal.init` is replaced in place by a per-world allocator (unique, monotonic, persisted in blocks). A primitive `int` → weak-reference hash index of live animals answers "who has this ID" in O(1). |
| 2, 10 duplicate loss | Duplicates are refused in `setAnimalID`, re-assigned when an animal enters the world, and fixed before its chunk unloads. Growth hands the parent's ID to its next stage explicitly; nothing is deleted over an ID. |
| 3 ghosts | Animals created on water are moved to land. Ghosts found on update, at chunk unload, at save, or beside a growing animal are removed with a removal that cannot throw. The constructor's duplicate check no longer runs before the ID exists. |
| 4, 5, 6 limbo | Every animal update, sound update and hutch update is isolated: a failure is logged with location and players, and the animal is frozen with back-off after 3 failures in a row instead of aborting the world update. |
| 7 `remove animals` | `/splint animals kill` with combinable selections (cell, area, player, hutch, trailer, inventory, type, stage, sex, age, place, ghosts, frozen, duplicates), dry run by default. |

### 12. Lab scenarios

Each scenario runs in a fresh session. Results: `vanilla` without Splint, `splint` with it.

RESULTS_TABLE

## Open questions

- The first ghost on the incident server is not identified. Water squares, a removed animal mod's type, and trailer unloading at the water's edge all produce one in vanilla. A log from before the first error, or `/splint animals census` on the affected cells with Splint installed, would show which one.
- Whether the rollback helped because it removed a ghost-producing situation from the save, or only because the restart cleared in-memory ghosts, cannot be told from the report. Ghosts left only on a square (finding 5) are never saved. Ghosts still in the update list (findings 3 and 4) are handed to the population save, where `IsoAnimal.save` dereferences their null data (code; not reproduced in the lab). A restart clears the in-memory ones until the situation that creates them recurs.
- A carried juvenile that reaches its growth age leaves the inventory: `grow` removes the inventory item, and the next stage is created at the animal's stored map position (lab `inventory-growth`). Where that position is and whether it is loaded decides where the grown animal ends up. This is the most concrete mechanism found for the "ghost chicken in my pocket" account, but it was not linked to the incident.
