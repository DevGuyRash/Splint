# Animal failure investigation

What was checked, how, and what was found about the multiplayer "world in limbo" incident (incident report, 2026-11-22). The report's own conclusions were treated as hypotheses to test. Everything here was measured on Project Zomboid 42.22.2.

## Contents

1. [The short version](#the-short-version)
2. [How this was tested](#how-this-was-tested)
3. [The failure: one half-built animal](#the-failure-one-half-built-animal)
4. [Where ghosts come from](#where-ghosts-come-from)
5. [Saving, restarting and the apop files](#saving-restarting-and-the-apop-files)
6. [Animals lost without an error](#animals-lost-without-an-error)
7. [How animals age and are stored](#how-animals-age-and-are-stored)
8. [The incident, clue by clue](#the-incident-clue-by-clue)
9. [What Splint does about each](#what-splint-does-about-each)
10. [Lab results](#lab-results)
11. [Still open](#still-open)

## The short version

- **What breaks:** vanilla can leave a half-built animal (a **ghost**) in the world: no type, no definition, no data, ID -1. Nothing in the world update catches errors per object, so one ghost aborts the rest of the update, every frame, for everyone on the server. Timed actions, player stats, Lua ticks and animal sync stop while the server process and console stay alive.
- **How it spreads:** a juvenile that grows within 4 squares of a ghost becomes another ghost. This is the exact stack in the report.
- **What starts it:** an animal built on a water square. Two ordinary player actions do that in the lab: unloading a livestock trailer backed up to water, and dropping a carried animal from a vehicle seat with the door over water. Other first causes have not been ruled out.
- **What it does to saves:** every save fails partway, although the console says "World saved". The failing save also empties the file of the ghost's own map cell (`apop_X_Y.bin`), destroying the animals an earlier save had stored there.
- **What clears it:** a restart. The ghost is only in memory and is never saved. Deleting `apop` files adds nothing except losing the animals in them. The failure returns when whatever made the ghost happens again.
- **What it is not:** the random, non-unique animal IDs. Those cause a different, silent bug: animals that vanish when they grow.
- **What Splint does:** keeps every reproduced failure from stopping the world update, a save or the rest of the map, and loses no animals in any lab scenario except ones the lab damaged beyond saving or whose type no longer exists.
- **What is not known:** which cause made the incident server's first ghost. See [Still open](#still-open).

## How this was tested

- **Game:** 42.22.2 (`version=42.22.2 3d39148846`), the dedicated server installed with steamcmd as app 380870, buildid 26486687. Its `java/projectzomboid.jar` is byte-identical to the client's (SHA-256 `3c4a7c5f…d120d287`), so the decompiled code (Vineflower 1.11.1) is exactly what the server runs. Line numbers refer to the decompiled sources, not to the runtime line numbers in the incident's stack trace.
- **Lab:** one dedicated server (`-nosteam`, loopback) plus one headless game client on a private Xvfb display, so chunks load around a real connected player. Default sandbox except zombies off. Each scenario (`lab/scenarios.py`) runs in a fresh session, without Splint and with it.
- **Restarts are real:** the server saves through its own `quit`, and a new session continues that save with the same player (`splintlab.py start --resume`).
- **In-game time** runs in a "time chamber": one real minute per in-game day and `settimespeed 20`, about 7 in-game hours per real second with the tick rate unchanged (measured by `time-chamber`). Scenarios wait for in-game hours, not real seconds. Juveniles are set half a day short of the growth age in the game's own stage table, so the game's clock makes them grow.

Each finding starts with how it is known: **Reproduced** (shown on the lab server, with the scenario's name), **Code** (read in the game's bytecode) or **Ruled out**.

## The failure: one half-built animal

### What a ghost is

*Code; reproduced: `water-seed`, `invalid-type`.*

Every `IsoAnimal(cell, x, y, z, type, …)` constructor first runs the `IsoGameCharacter` constructor. That constructor adds the object to the cell's update list (`cell.addMovingObject`) and to its square (`setMovingSquareNow`) whenever the coordinates are not 0,0,0 (IsoGameCharacter.java ~782, ~817). Only then does `IsoAnimal` decide whether to initialise. It returns early, leaving the object in the world uninitialised, when:

- the square is water (`checkForWater()`);
- the type has no definition (`adef == null`);
- `checkForChickenpocalypse(null)` matched. The new animal's ID is still -1 at that point, so this only happens when another ghost is within 4 squares.

`addAnimal` of a hen on a Fallas Lake water square returned an animal with `id=-1 data=nil type=nil`, standing on the square.

Vanilla builds animals at arbitrary squares when they grow (at the parent's position), leave a trailer (`AnimalCommandPacket` RemoveAnimalFromTrailer, at the trailer's "AnimalEntry" area), are dropped, hatch, are born or are reanimated, and in chunk, ranch and vermin spawns. None of these check the result.

### One ghost stops the world for everyone

*Reproduced: `water-seed`.*

`IngameState.updateInternal` wraps the whole world update in a single `try`. There is no per-object catch in `IsoCell.ProcessObjects` or `MovingObjectUpdateScheduler`. A throw from one animal skips every object after it, `IsoCell.updateInternal`'s tail, `AnimalController`, `onTick` (Lua `OnTick`) and `updateManagers` (`ActionManager`, `TransactionManager`). On a server the catch returns and the next frame does the same.

With one water ghost on a vanilla server:

- `NullPointerException: Cannot read field "turnDelta" because "this.adef" is null at IsoAnimal.update`, once per frame: 757 in the first 16 s, then about 10 per second (the incident report: 12,000 in 22 min, about 9.1/s).
- The server's Lua `OnTick` heartbeat stopped immediately.
- The connected player's server-side hunger and thirst stopped changing (0.0403 / 0.0343, unchanged over 10 s).
- After teleporting 3,500 squares away, the client had the new squares, but none of the 460 animals standing there were sent to it.
- The errors continued with no player anywhere near the ghost: it stays in the server's global update list.
- The console kept working.

This matches the report's "no players can take any actions… endurance and thirst/hunger stop changing… no timed actions work… everything is in limbo", and "even where no one is". It also means the players who notice can be far from the ghost.

### It spreads when an animal grows beside it

*Reproduced: `grow-near-ghost`, `grow-near-invalid-ghost`. This is the report's stack (`textureSkinned`).*

When a juvenile within 4 squares of a ghost reaches its growth age, `AnimalData.grow` constructs the next stage. The constructor's duplicate check compares -1 with -1, matches the ghost, and calls `delete()` on the new, uninitialised animal. `delete` → `removeFromWorld` → `IsoGameCharacter.removeFromWorld` → `releaseRagdollController` → `getAnimationPlayer` → `ModelManager.getBodyModel` → `AnimalVisual.getModelTest` reads `AnimalDefinitions.getDef(null).textureSkinned` and throws. That is the report's exact frame sequence.

`IsoMovingObject.removeFromWorld` had already taken the object out of the update list, but `delete` never reaches `removeFromSquare()`. So the failed new stage stays on the square as a ghost, the parent is unchanged, and the growth repeats next frame.

### Its map area never unloads

*Reproduced: `unload-with-ghost`, `presence-cycle`. These are the report's `getAttachedTree` errors.*

`IsoAnimal.unloaded()` calls `getData().getAttachedTree()` (IsoAnimal.java:1652). Chunk unload (`AnimalPopulationManager.removeChunkFromWorld`) calls it for every animal on the chunk's squares, ghosts included, giving exactly "Cannot invoke AnimalData.getAttachedTree() because IsoAnimal.getData() is null". Vanilla catches it at chunk level, after it has skipped the rest of that chunk's population bookkeeping. The ghosts that growth leaves on a square, outside the update list, are the ones that reach it. So the report's two signatures fit one ghost population: `textureSkinned` while juveniles keep trying to grow, and `getAttachedTree` when their chunks unload. `unload-with-ghost` produced exactly that pair, 723 of each, once per retried unload of the ghost's chunk.

The unload then fails for the whole map cell, every tick. `ServerMap.postupdate` has one `try` around its loop over the loaded cells. When the ghost's cell throws, every cell after it in the list is neither updated nor unloaded, and since a cell is only taken off the list after a successful `Unload`, the same happens every tick. `ServerCell.Unload` hands each chunk to the save queue and then drops it, so the chunks before the ghost's are already gone (49 of 64 here) when it throws. If a player comes back, `loadOrKeepRelevent` finds the cell still present and only marks it needed, so the dropped chunks are not loaded again until the cell has really unloaded. They were queued for saving, so nothing in them is expected to be lost from disk; that was not tested.

In `presence-cycle` a player leaves a ghost at Fallas Lake (on its square, not in the update list) and returns, twice, travelling on in between; moving by teleport stands in for other players staying online. `presence-cycle-control` is the same trip without a ghost. The third column is Splint with the animals module off (`SPLINT_SERVER_JVM=-Dsplint.disable=animals`), so the ghost is still there when its area unloads and only the map isolation is tested.

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

The vanilla chunk counts were the same in an earlier run. The control's player died of an unrecorded cause late in its run, after the phases above. This kind of ghost does not stop the world update; `unload-with-ghost` also stopped it because its ghost was still in the update list.

### The console's `remove animals` does not clear it

*Reproduced.*

`RemoveCommand` only stores the issuing connection in `GameServer.removeAnimalsConnection`. The main loop runs the removal only when that connection is not null, and a console command has no connection. The console prints "Animals removed" and nothing happens: the lab's error rate was unchanged (100 per 10 s before and after). From in-game it removes only animals registered with `AnimalInstanceManager`, which ghosts never are.

## Where ghosts come from

| Source | Result on vanilla | Scenario |
|---|---|---|
| Unloading a livestock trailer backed up to water | **Ghosts** | `trailer-unload-water` |
| Dropping a carried animal from a vehicle seat, door over water | **Ghost** | `drop-on-water` |
| Anything else that creates an animal on a water square (a mass spawn overlapping a river) | **Ghosts** | `water-seed`, `stress-5000` |
| Growing within 4 squares of a ghost (needs a ghost already) | **Another ghost** | `grow-near-ghost` |
| Creating an animal of a type nobody defines (a faulty mod or command) | **Ghost** | `invalid-type` |
| Loading a loose animal whose mod was removed | No ghost; the animal does not load | `removed-type` |
| Dropping a carried animal on foot at the water's edge | No ghost: water squares are never a drop target | `drop-on-water` |
| A pasture built over water, fenced or not | No ghost | `pasture-pond`, `pasture-pond-unfenced` |
| Logging in next to a trailer, carrying or butchering chickens, visiting Ovo Farms, trailer egg compartment, dead animals from a trailer | No ghost | [clue by clue](#the-incident-clue-by-clue) |

On an unmodded server the only first causes reproduced through ordinary play are at water. Others have not been ruled out. A ghost on dry land is possible: growth beside one leaves the new ghost where the juvenile stood.

### Unloading a trailer at the water's edge

*Reproduced: `trailer-unload-water`.*

`BaseVehicle.removeAnimalFromTrailer` builds the animal anew (`new IsoAnimal(...)`) at the centre of the trailer's `AnimalEntry` area, about one and a half tiles behind it, then copies the old animal into it; `ISRemoveAnimalFromTrailer` adds it to the world. When that spot is a water square, the constructor stops early and the copy restores the ID and data but not the type. Two hens unloaded from a trailer backed up to Fallas Lake came out with `type=nil`; the world update stopped at once (424 `turnDelta` errors, Lua ticks dead, player stats frozen).

This is the most concrete candidate for the incident's first ghost, because the report ties the failure to a player with a trailer full of chickens. It is not proven to be the one on that server.

### Dropping a carried animal from a vehicle seat over water

*Reproduced: `drop-on-water`.*

The drop puts the item on the door's square (`ISDropVehicleItemAction`, `IsoGridSquare.AddWorldInventoryItem`). For an Animal item the game builds a new `IsoAnimal` there and copies the carried one into it, as trailer unloading does, with no water check. The result was a ghost (`type=nil`), 393 `turnDelta` errors, the world update stopped and player stats frozen.

On foot the same chick lands on the player's land square. Water squares are never a drop target (`ISInventoryTransferAction:canDropOnFloor`), even with all seven land squares around the player full.

### A type nobody defines

*Reproduced: `invalid-type`, `removed-type`.*

Creating an animal of an undefined type leaves a ghost (`addAnimal` in `invalid-type`). Loading one from a save does not. `removed-type` simulates a removed animal mod by renaming one saved turkey's type in the stopped server's `apop` file. On the next start `IsoAnimal.load` throws (`adef` is null) and `AnimalCell.load` catches it. The world update kept running, there was no ghost, the turkey was gone and the six hens stored before it in the file were there; the start after that was clean. Splint behaved the same: it does not change loading. The animal under construction has coordinates 0,0,0, so it never enters the update list. Animals in trailers, hutches and pockets were not tried.

### A pasture over water

*Ruled out: `pasture-pond`, `pasture-pond-unfenced`.*

No game code builds pasture animals at arbitrary pasture squares (every place that constructs an animal at given coordinates was listed from the bytecode), so the question is whether pasture animals end up standing on water, where growth would build their next stage. A fenced pasture was made the way the game's pasture tool does (`DesignationZoneAnimal.new` and `createSurroundingFence`) over the Fallas Lake shore, 284 of its 806 squares open water, with 120 chicks, 20 lambs and 20 piglets growing over the following week and 30 hens. A server ticker sampled every animal's square while five in-game days passed: no animal ever stood on a water square, juveniles grew normally, and there were no ghosts or exceptions, on vanilla and with Splint (Splint's water relocations and ghost counters stayed at zero). Without the fence (vanilla only) the result was the same.

## Saving, restarting and the apop files

Loose animals are stored in `apop/apop_X_Y.bin`, one file per 256×256 map cell. The save writes loaded animals there too. Animals in trailers, hutches and pockets are saved with the vehicle, the map chunk and the player instead.

### A ghost makes every save fail

*Reproduced: `ghost-save-restart`, vanilla `stress-5000` beside water.*

- **`save` (what scheduled saves run):** the console prints "World saved" at once, then the save writes the map and the players and throws in the animal population save (`IsoAnimal.save`: "this.data" is null, through `AnimalPopulationManager.save` → `AnimalManagerWorker.save` → `VirtualAnimal.save`). "Saving finish" never comes. After a restart the player was where they had been, but 5 of the 6 hens placed before the save were gone. The one that remained may not be one of them: `apop-wipe` also found one hen there after every `apop` file was deleted.
- **`quit` (a restart):** the shutdown save hits the same exception in its own thread, which dies; the save never finishes and the server has to be killed.
- **While running:** when a mass spawn overlapped a river, 303 of the new animals came out half-built and the world update stopped; later the population worker thread itself died (`Exception in thread "Thread-0" java.lang.NullPointerException: Cannot invoke "AnimalData.getBreed()" because "this.data" is null`, through `VirtualAnimal.save` → `AnimalChunk.save` → `AnimalCell.save` → `AnimalManagerWorker.save`). That is the signature of reference R17 in the incident report, there attributed to a heavily modded single-player save; here it happens unmodded on a dedicated server. Without that thread the server no longer saves or restores animal populations at all.

Stock servers save only when areas unload (`SaveWorldEveryMinutes=0`), so failing saves can go unnoticed for a long time.

### The failing save empties the file it was writing

*Code; reproduced: `damaged-save-ground`, `ghost-save-restart`.*

`AnimalCell.save` opens its `apop` file for writing, which empties it, before it serialises the animals, and catches only `IOException`. When an animal throws, the file stays at 0 bytes. `AnimalManagerWorker.save` has no catch around its loop, so the map cells after that one are not written either.

In `damaged-save-ground` nine hens were saved by a full save that finished. A second save then failed on one damaged hen: their file was 0 bytes, and after the restart none of the nine were there. The earlier good save did not protect them, because it was in the same file.

Loading an empty file logs `newLimit < 0: (-1 < 0)` from `AnimalCell.load` and continues as if the cell had never been saved. Its animal zones are already marked as spawned (in `map_animals.bin`), so they do not spawn their animals again. Only a backup brings those animals back. To check a server, look for 0-byte `apop_*.bin` files, or for that message in the console after a start.

### Any animal that cannot be written does the same

*Reproduced: `damaged-save-ground`, `-hutch`, `-trailer`, `-pocket`, `-ground-unrepairable`.*

A ghost is not the only animal that breaks a save. `IsoAnimal.save` writes the breed's name, so a complete animal that has lost its breed throws too. No vanilla path that produces one was found (the lab removes the breed on purpose, as a faulty mod could), but where such an animal is decides what is lost with it:

| The damaged animal is | Vanilla | Splint 0.4.0 | Splint 0.4.1 |
|---|---|---|---|
| on the ground | Shutdown save dies; `apop` file emptied; 9 of 9 hens gone after the restart | same | Save finishes; all 9 there |
| in a hutch | Save finishes, but the hutch's whole map chunk is not written and keeps its older copy | same | No error; all 3 chicks there |
| in a trailer | Save finishes, but the trailer is not written and keeps its older copy | same | No error; all 3 hens there |
| in a pocket | Shutdown save dies while writing the player, before the loose animals | same | Save finishes; all 3 chicks there |
| on the ground, with no descriptor (damage Splint does not repair) | Shutdown save dies; `apop` file emptied; 9 of 9 hens gone after the restart | not run | Save finishes; the other 8 of 9 there, the damaged one logged and left out |

Splint 0.4.0 dropped only ghosts at this point, so its claim that one bad animal could no longer abort a save was too broad. 0.4.1 fixes it; see [What Splint does](#what-splint-does-about-each) for how and for its limit.

### A restart clears the ghost; wiping apop only deletes animals

*Reproduced: `apop-wipe`, `apop-wipe-control`.*

A common suggestion is to stop the server and delete the `apop` files. In both runs the player visited Ovo Farms, a full save finished, a ghost stopped the world and the server was restarted:

| After the restart | Restart only | Restart and `apop` deleted |
|---|---|---|
| Ghosts | none | none |
| World update | running | running |
| Ovo Farms' animals | 11 of 11 | **0 of 26** |

The ghost exists only in the running server's memory and cannot be saved, so stopping the server removes it either way. Deleting the files removes the stored animals as well, and they do not come back, because the record that those zones have already spawned is kept in another file (`map_animals.bin`). Deleting them while the server runs changes nothing about the ghost, and the server writes them again.

That is also why the incident server's 20-hour rollback worked: it was a restart, on a save from before the ghost. Any restart clears the ghost but loses what failed to save. The failure comes back when whatever made the ghost happens again.

## Animals lost without an error

### Animal IDs are random, 0 to 9999, and not unique

*Code; reproduced: `mass-growth`, `duplicate-ids`.*

`IsoAnimal.init(AnimalBreed)` sets `this.animalId = Rand.Next(10000)` (IsoAnimal.java:1506). The field starts at -1 (line 138). Nothing checks the new value against existing animals. With n animals the expected number of colliding pairs is n(n−1)/2 / 10000. The lab's 460-animal farm held 10 duplicate pairs, against 10.6 expected.

The network ID (`onlineID`, `AnimalInstanceManager.allocateID`) is separate and unique among registered animals; it is not the ID the game uses to match mothers, babies, nest boxes, trailer lookups and the duplicate check.

### Growing next to an animal with the same ID

*Code; reproduced: `mass-growth`, `duplicate-ids`.*

`AnimalData.grow` builds the next stage, copies the parent's ID onto it, then calls `parent.checkForChickenpocalypse(newAnimal)` (AnimalData.java ~1402). If any other animal within the 8×8 window shares the ID, that check deletes the parent and returns true, and `grow` then deletes the new stage too. The animal is gone with no error. In the first mass-growth run, 460 juveniles grew and 459 animals remained.

### Unloading next to an animal with the same ID

*Code; lab `legacy-dup-reload`.*

When a chunk unloads, its animals join virtual herds in the population worker. `AnimalManagerWorker.addAnimal` (AnimalManagerWorker.java ~197-219) removes any joining animal whose ID already exists in the herd, logging only at debug level ("Trying to add an animal with an existing IsoAnimal.animalID"). Two same-ID animals near each other therefore become one the next time nobody is around. This is independent of growth.

### Growing while standing on water

*Code; storage places reproduced: `stored-on-water-growth`.*

After building the next stage, `AnimalData.grow` calls `parent.checkForWater()` and deletes both when it returns true. An animal on a water square when it grows disappears without a log line. Animals in a trailer, an inventory or a hutch are not affected in practice: the game clears their square when they are stored, so the check finds no water (trailer chicks with a water position grew normally on vanilla and with Splint).

### Young animals left without feeders

*Reproduced: `pasture-pond`. This is the game's animal care working as coded, not this failure.*

When the player left the pasture over water for three in-game days and came back, the juveniles were dead and the adults alive: every missing ID was matched by a corpse in the pasture (102 chicks, 20 lambs, 17 piglets), none anywhere else. The pasture had no feeder or trough. The same juveniles had lived through five loaded in-game days there, on its grass with the lake inside the fence, but the catch-up applied when the area loads again (`IsoAnimal.updateStatsAway` → `AnimalData.hourGrow` → `updateHungerAndThirst`, `updateHealth`) raises hunger and thirst hour by hour, and evidently does not count the grass and the lake. Predators were not involved: the away predator roll only runs with `AnimalMetaPredator`, which was off. A pasture that keeps animals alive while players are around can lose its young while nobody is there, unless it has feeders and troughs.

## How animals age and are stored

### Nothing ages faster in an empty chunk

*Code; measured: `aging-rates`. The report's "animals age 10x faster in a chunk no one is in" is refuted as stated.*

Aging speed is the sandbox option `AnimalAgeModifier`, applied the same way to loaded and unloaded animals (`AnimalData.getAgeGrowModifier`): Ultra Fast 90×, Very Fast **10×**, Fast 5×, Normal 1×. Slow and Very Slow are also 1×: they are not implemented. The default presets use Normal (Apocalypse, Survivor, Builder) or Fast (Outbreak). A server on "Very Fast" ages every animal 10×, everywhere.

What differs is how the time is applied:

- **Loaded, nobody near:** on a dedicated server every animal is updated every frame (the scheduler's distance throttling is client-only). Age advances by `modifier` days per in-game day.
- **Unloaded, inside a player's animal zone:** on reload, `DesignationZoneAnimal.doMeta` calls `updateStatsAway(hours)`, which adds `floor(hours/24) × modifier` days in one step. Several growth stages can then pass on consecutive frames. That is a burst of growth right when a player arrives, which fits "loading OvO Farms and leaving it" as a trigger for growth-related failures.
- **Wild or virtual animals** do not age while virtual.

Measured days of age per in-game day, at Normal speed:

| Place | Slow clock (1 real minute per day) | Time chamber |
|---|---|---|
| On the map | 0.99 | 0.82 |
| Livestock trailer | 0.99 | 0.82 |
| Hutch | 0.99 | 0.82 |
| Carried | 0.5 | 0.0 |

Carried animals barely age (ages are whole days, so 0.5 is coarse). The chamber runs every place about 18% slow, a limit of the chamber; rates are read at the slow clock.

### Trailers

*Reproduced: `trailer-growth`, `trailer-relog`.*

Juveniles in a livestock trailer age while it is loaded (125 to 128 days in 66 in-game hours), with no catch-up, grow in place and keep their IDs (`replaceGrownAnimalInTrailer`). The next stage is still constructed at the trailer animal's stored coordinates first, so the constructor's water and duplicate checks run there. In the chamber only one of six grew: the trailer updates its animals on a real-time limiter, so a test that passes days in seconds starves it. That is a limit of the chamber, not a game bug.

### Hutches

*Reproduced: `hutch-growth`, `hutch-pocket`.*

Chicks past their growth age stay chicks while inside (`IsoHutch.updateAnimalInside` has no `checkStages`) and keep the hutch tiles as their coordinates, across a restart. On release they all grow on the next frames, at the hutch's exit square. A hutch therefore concentrates growth events in one spot, which is exactly where a nearby ghost does the most damage. Taking one out after a restart, as `ISHutchGrabAnimal` does, puts it in the pocket cleanly, on vanilla and with Splint.

### Pockets and the trailer's egg compartment

*Reproduced: `inventory-growth`, `pocket-far-growth`, `pocket-relog`, `trailer-egg-compartment`.*

- **Carried animals do not age.** A chick carried for 65 in-game hours stayed at 125 days (its hours survived moved by 4), so it never reached its growth age.
- **A carried animal grows only if it is already past its growth age** (set by the lab in `inventory-growth`). `AnimalData.grow` then builds the next stage at the coordinates the animal carries (where it was last on the ground or in a hutch), removes the item and adds the grown animal to the world there, not next to the player.
- **After a relog, a carried animal is inert.** It keeps its stored coordinates but loses its container link (`getContainer()` is null) and does not grow even when set past its growth age. With a null container, `grow` would delete the old animal without removing its item, but carried animals are not updated after a relog, so the lab could not reach that path.
- **Egg-compartment chicks are frozen in time.** `Food.checkEggHatch` builds a chick hatching in a trailer container at the trailer's square, then removes it from the world and wraps it in an Animal item in that container. Like a carried animal it neither ages nor gets hungry or thirsty, loaded or not, which explains live chicks in a trailer abandoned long enough for the hens to die. Taking them out does not kill them: no catch-up runs when they enter the world.

### Zombies

*Code; reproduced: `server-zombie-update`.*

The dedicated server does not update zombies, so a zombie cannot stop its world update the way a ghost does, and there is nothing server-side for Splint to guard. `MovingObjectUpdateScheduler.startFrame` takes every `IsoZombie` aside when `GameServer.server` is set: it only adds it to its target player's chasing count (and calls `updateForServerGui` when the server runs with its GUI), without adding it to an update bucket. Connected clients simulate zombies; the server relays them. In the lab a zombie in the server's update list received no `OnZombieUpdate` events, and clearing its legs sprite, which `IsoZombie.updateInternal` dereferences, threw nothing, on vanilla and with Splint. Neither the incident report nor the reports it cites involve zombies.

## The incident, clue by clue

### From the report

| Clue | What was found |
|---|---|
| "Everything is in limbo", "even where no one is" | [One ghost stops the world for everyone](#one-ghost-stops-the-world-for-everyone) |
| `textureSkinned` stack | [It spreads when an animal grows beside it](#it-spreads-when-an-animal-grows-beside-it) |
| `getAttachedTree` errors | [Its map area never unloads](#its-map-area-never-unloads) |
| Animal IDs are not unique | True, but a [different, silent bug](#animals-lost-without-an-error) |
| Animals age 10x in empty chunks | [Refuted as stated](#nothing-ages-faster-in-an-empty-chunk) |
| The 20-hour rollback helped | [It was a restart on a save from before the ghost](#a-restart-clears-the-ghost-wiping-apop-only-deletes-animals) |

The following clues produced no ghost, no exception and no stopped world update, on vanilla or with Splint:

| Clue | Scenario | Result |
|---|---|---|
| A player logs back in next to a trailer full of chickens | `trailer-relog` | All 8 animals intact after save and relog; the chicks grow in the trailer later |
| "I butchered some chickens before I logged" | `weapon-kill-butcher` | One hen killed with a weapon from each weapon category the game defines (9, read from the item scripts: ranged, axe, blunt, improvised, long blade, small blade, small blunt, spear, unarmed), 8 carcasses butchered the game's way (626 items), one carried; save, relog and two in-game days are clean |
| "Possibly a ghost chicken in my pocket" (carcasses) | `carcass-relog` | Three carried chicken carcasses survive save, relog and two in-game days |
| "Loading OvO Farms and leaving it" | `ovo-farms` | Ovo Farms (1245 Payne Road, Echo Creek, three chicken ranch zones around 3960,10805) visited, left for 2.5 in-game days twice, chicks growing while it is unloaded: no errors, no ghosts. Animals of a reloaded farm come back from the population worker some time after the area loads, so a count right after arriving can be short; Splint's run found all 7 IDs again on the next visit |

### From the player at the centre of it

The player whose farm was at the centre of the incident described it later: an abandoned livestock trailer with about six dead hens and six chicks; chicks that "hatched into the egg compartment"; one chick that "said it was alive, but it plopped onto the ground dead"; one that gave "a blood animation without any body to butcher"; the rest carried in the pocket and dropped at a farm built over water, which the server lost track of. Every time they joined before the rollback the server "crashed", and they and one other player were put back on the same spot at every login, across resets. Each step was tested:

| Step | Scenario | Vanilla | Splint |
|---|---|---|---|
| Drop a carried chick on foot at the water's edge | `drop-on-water` | Lands on the player's land square | same |
| Drop it from the seat of a car parked at the water, door over water | `drop-on-water` | **Ghost**, world update stopped | Whole chick, no exceptions, world update running |
| Unload dead, dying and healthy hens and chicks from a trailer on land, grab one and drop it | `trailer-unload-dead` | Dead ones become corpses at the trailer's entry, living ones come out whole; no ghosts, no exceptions | same |
| Hatch two eggs in the trailer's egg compartment, leave it 5 in-game days loaded and 5 unloaded, take the chicks out to the ground and through the pocket | `trailer-egg-compartment` | Chicks hatch as Animal items in the compartment and stay at age 1, hunger 0, thirst 0 throughout; both come out healthy | same |
| The farm over water had no fence | `pasture-pond-unfenced` | No animal ever on a water square, no ghosts; the young starve in the catch-up after three days away | not run |

What that explains:

- **"The second I joined the errors stopped. Then when I left, the errors exploded"** is reproduced by [`presence-cycle`](#its-map-area-never-unloads). The errors depend on whether anyone is near the ghost, not on which player it is. The server crash on leaving is not reproduced.
- **The server got slower and slower until its reset.** The map cells that pile up (9 to 50 over a short trip) are a steady memory cost that only a player returning to the ghost, or a restart, clears. That fits, but tick rate did not fall in the lab with one player, so the slowdown itself is not reproduced.
- **Repeat "crashes" and being put back on the same spot** are partly explained. A ghost in the update list stops the world update, and the server then stops moving its copy of the players, so the position it saves is where they stood. A ghost that is only on its square leaves holes in the server's map around it, and a player in a hole is frozen on the server while others are not, which fits "everyone was stuck in our chunk, just me and one other person". Being put back at login was not measured, and what brought the ghost back after each restart is not known.
- **Losing every live chicken but keeping one corpse** fits failed saves: live animals are written by the population save that the ghost breaks, while corpses are saved with the map chunk.
- **Not reproduced:** the chick that came out dead and the death with no body. One lab trailer that the world never took in (a debug spawn on one spot near the lab's spawn point; the scenario now checks for this) put unloaded animals at its bare script offset near 0,0 with no square, and dead ones' corpses nowhere, which looks like both; but a trailer a player stands next to is always in the world, so that is a lab artifact, not an explanation.

## What Splint does about each

| Failure | Splint |
|---|---|
| [A ghost forms](#what-a-ghost-is) | Animals created on water are moved to the nearest land before they are initialised, so they come out whole. Ghosts found on update, at chunk unload, at save or beside a growing animal are removed with a removal that cannot throw. The constructor's duplicate check no longer runs before the ID exists. |
| [The world update stops](#one-ghost-stops-the-world-for-everyone), and [it spreads](#it-spreads-when-an-animal-grows-beside-it) | The update scheduler's per-object calls (preupdate, frameStep, update, postupdate) are each isolated for every moving object, as are animal sound and hutch updates: a failure is logged with location and players, and the object is frozen with back-off after 3 failures in a row instead of aborting the world update. The lab's damaged-animal case (`malformed-breed`) failed in postupdate, which an update-only guard would have missed. |
| [A map area never unloads](#its-map-area-never-unloads) | The server's per-cell map work is isolated (`core.isolate.MapIsolation`): an object that cannot be removed is skipped, a chunk that cannot be unloaded is still saved, and a cell that fails does not keep other cells from updating and unloading. `build.py --verify` unloads a real map cell whose 64 chunks all fail. |
| [`remove animals` does nothing](#the-consoles-remove-animals-does-not-clear-it) | `/splint animals kill` with combinable selections (cell, area, player, hutch, trailer, inventory, type, stage, sex, age, place, ghosts, frozen, duplicates), dry run by default. |
| [A ghost makes every save fail](#a-ghost-makes-every-save-fail) | Ghosts never form or are removed before the save. One that still reaches a herd being saved is dropped before the herd is counted (`VirtualAnimal.save`). In `ghost-save-restart` with Splint, `save` and `quit` finish without errors and the restart has all 6 hens, plus the animal built on water as a whole hen on land. |
| [Another animal cannot be written](#any-animal-that-cannot-be-written-does-the-same) | An animal with no breed is given its type's first breed when it is saved, anywhere (`IsoAnimal.save`); vanilla's `AnimalData` constructor also picks one from the definition. A loose animal that still cannot be written is left out of the file and stays in the game, and the rest of its herd is written. `build.py --verify` checks both against the real save methods (`HerdSaveCheck`). |
| [Random IDs](#animal-ids-are-random-0-to-9999-and-not-unique) | `Rand.Next(10000)` in `IsoAnimal.init` is replaced in place by a per-world allocator (unique, monotonic, persisted in blocks). A primitive `int` → weak-reference hash index of live animals answers "who has this ID" in O(1). |
| Animals lost to duplicate IDs, [growing](#growing-next-to-an-animal-with-the-same-id) or [unloading](#unloading-next-to-an-animal-with-the-same-id) | Duplicates are refused in `setAnimalID`, re-assigned when an animal enters the world, and fixed before its chunk unloads. Growth hands the parent's ID to its next stage explicitly; nothing is deleted over an ID. |
| [Growing on water](#growing-while-standing-on-water) | The animal is moved to land instead of deleted. A stored animal is never deleted for it either, in case one keeps a square. |

Limits and details:

- **Unrepairable animals elsewhere:** leaving an unsaveable animal out covers loose animals only. One in a hutch, trailer or pocket with damage other than a missing breed still fails that save as in vanilla; no such animal has been seen.
- **Dropping from a vehicle seat:** the constructor guard moves the new animal to land, but `AddWorldInventoryItem` then sets it back on the drop square, so it stands whole on the water square. It is initialised, nothing throws, and at growth Splint moves it to land.
- **Unloading beside a ghost:** in the core-only run vanilla's own handler still logged one `getAttachedTree` and skipped the rest of that chunk's population and pathfinding bookkeeping. With the animals module on, the ghost is removed first and the run logged no errors, so that full run does not test the unload guard; the core-only run does.
- **A map cell that still holds chunks after failing is not dropped:** the failure goes on to vanilla's handler, which keeps the cell and retries, because dropping it would lose chunks that were never saved. `preupdate` already isolates its own unload per cell in vanilla.

## Lab results

Each row is one scenario, first without and then with Splint (`lab/report.py` builds this table from the runs' `results.json`). "World update stopped" means the server's Lua tick heartbeat, which runs inside the world update, stopped. Stress rows count animals in the server's whole update list, with the player invincible (grown rams and boars attack, and a dead player would unload the area), on the stock 8 GB server heap; the vanilla and Splint stress runs were interleaved, because a shared machine's load changes from run to run. Scenarios described in the sections above are not repeated here.

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

- Duplicate-ID loss in vanilla is random. Whether a growing animal has a same-ID neighbour within 4 squares varies, so the counts vary between runs: `mass-growth` lost 0, 1 and 4 animals in three vanilla runs. With Splint, no run has lost an animal.
- `legacy-dup-reload` did not trigger vanilla's herd-join drop in this run; the duplicate simply survived. With Splint it was repaired.
- `inventory-growth`, `trailer-growth` and `hutch-growth` behave the same with and without Splint; they document how growth works in each storage place.
- At 5,000 animals the server slows to about one tick per second with or without Splint; that is the game's own animal simulation.

### Speed

Four interleaved runs (vanilla, Splint, vanilla, Splint), measured as the dedicated server's CPU milliseconds per world tick across all its threads, mean of two runs (individual runs in brackets). Lower is better. CPU time per tick is far less sensitive to other load on the machine than ticks per second, which varied by up to 50% between identical runs here.

| Animals | Vanilla | Splint |
|---|---|---|
| 2,000 loaded | 251 ms (286, 217) | 214 ms (190, 238) |
| 2,000 after growth | 176 ms (201, 150) | 133 ms (140, 127) |
| 5,000 loaded | 978 ms (1,022, 935) | 901 ms (844, 958) |
| 5,000 after growth | 670 ms (580, 760) | 634 ms (587, 681) |
| animals lost in growth | 5, 5 and 18, 21 | 0, 0 and 0, 0 |

Splint adds no measurable cost. Its means were lower in all four rows, but the runs overlap, so no speed-up is claimed. These runs predate the save repair, which costs two field reads when an animal is saved or sent to a client in full.

## Still open

- **Which source made the incident server's first ghost.** A trailer unloaded, or a carried animal dropped from a vehicle seat, over water fit the report and the player's account best. With Splint installed, every removed ghost is logged with its place, coordinates, map cell and nearby players, and `/splint problems` lists them, so a recurrence would name it.
- **What brought the failure back at each login before the rollback,** when a ghost is never saved, and what the server crash was. The console log of the recurrence would show which errors came first.
- **How the original `getAttachedTree` reports relate in time to the `textureSkinned` ones** on that server; both come from the same ghosts in the lab.
