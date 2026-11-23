# Splint

Splint is a fix, optimisation and admin-tools framework for Project Zomboid Build 42, in the spirit of LuaBuddy and ZombieBuddy: a small core that every feature module plugs into. The first module, `animals`, fixes the multiplayer animal failure that stops the world update (unique IDs at the source with an O(1) ID index, no half-built animals, per-animal failure isolation, removal that always completes, `/splint animals`). Later modules can be server or client side.

## Architecture

The game only runs mod Lua in Kahlua, and these failures are in Java, outside anything Lua can intercept, so Splint is a Java agent (`agent/src`) that rewrites a short, explicit list of game methods in memory as the JVM loads them. It never writes game files and never ships game code.

- `splint.core` is the only place for shared machinery. Each concern has exactly one home:
  - `core.module`: the `SplintModule` SPI. Modules are listed in `agent/resources/META-INF/services/splint.core.module.SplintModule`. They are enabled by default; `-Dsplint.disable=` and `-Dsplint.modules=` switch them, and `Side` says where a module runs.
  - `core.patch`: `Patcher` (method replacement, expression redirect, call-site redirect, added fields), `Originals` (handles to original bodies) and `PatchRegistry`, the single record of what is declared, applied, pending and missing, which also owns the transformer.
  - `core.log`: `Log` (console/stdout and `Logs/*_Splint.txt`, rate-limited `problem`, `boot` for the transformer) and `Incidents` (what happened where, and who was there).
  - `core.world`: map cells, areas, players.
  - `core.command`: the one game command `/splint`, its dispatcher, global commands (`help`, `status`, `modules`, `problems`, `players`), `Args`, `AreaTerms` and `CommandContext`.
  - `core.isolate`: per-object update isolation. The update scheduler's per-object calls go through `Isolation`, and `Breaker` (state in a field added to `IsoMovingObject`) contains failures and freezes repeat offenders. `Describers` lets modules name their objects in logs.
  - `core.util`: data structures such as `IntWeakMap`; `ObjectSlot` and `Threads` (main-thread queue) sit in `core`.
  - `Counters`, plus `Splint.tick()`, the per-frame heartbeat driving module ticks.
- A module (`splint.<area>`) declares its patches, redirects, commands, counters, status and per-player info. It owns no plumbing. Anything a second module would also need belongs in core.
- Every patch has a reproduced failure behind it, recorded in `docs/investigation.md` with the lab scenario that shows it. Do not add speculative patches. Fix at the source (a redirect or a replacement of the faulty logic) rather than overwriting results afterwards.
- Hooks keep the original callable as `splint$<name>` through a per-hook holder class (`Originals`), so one patch that fails to apply cannot break another.
- Modules are constructed in premain, before the transformer exists: a module's constructor and static fields must not touch game classes (use class names, as `Describers.register` does), or those classes load unpatched. `PatchRegistry` reports any target that loaded too early.
- Work that game state owns runs on the main thread. The dedicated server loads chunks on a loader thread; hooks reached from there do only thread-safe work and hand the rest to `Threads.onMain`.

## Keep it fast

- Per-object, per-tick hooks may only do field reads and volatile reads on the normal path: no allocation, no locks, no hashing, no logging. `Quarantine.isFrozen` is a volatile read while nothing has failed.
- Lookups by ID go through hash structures with primitive keys (`IntWeakMap`), never square scans or boxed maps.
- Expensive work (player lookups, `describe`, `Incidents.record`) runs only when something went wrong, or on an admin command.

## Rules learned the hard way

- A hook must never throw into the game loop for an object-specific problem. Catch, log with context, and continue. Rethrow `VirtualMachineError`.
- Inside the class transformer, call nothing from the game and never use `System.out` (the game replaces it with its own logger). Use `Log.boot`. The JVM silently discards a transformer's result when it throws, and `IsoAnimal` loads before the game's logging exists, so a logging call there once left the server running completely unpatched.
- To take an animal out of the update list, remove it from `IsoCell.getObjectList()` directly, as vanilla `IsoAnimal.removeFromWorld` does. The cell's deferred remove list skips every living `IsoPlayer`, and animals are `IsoPlayer`s.
- Admin output goes only to the admin who ran the command (console, RCON, chat reply) and to the logs; nothing is broadcast to players. Destructive commands are dry runs until `confirm`, and every change is audited with the admin's name.
- Verify every game method you call exists in the installed build (`javap -cp projectzomboid.jar`). After a game update, run `python3 build.py --verify` to see which patches no longer apply.

## Build and verify

- `python3 build.py --test --verify` compiles against the installed `projectzomboid.jar` (compile-time only), runs the unit tests in `agent/test`, writes `dist/splint.jar`, and applies every module's patches offline under the JDK verifier.
- `lab/splintlab.py` runs a disposable loopback multiplayer session: the steamcmd dedicated server in `.lab/server` (app 380870), an Xvfb display and a headless client from the local game install. `lab/scenarios.py run .lab/runs/<new-dir> [--agent dist/splint.jar]` runs every failure scenario in its own session (or one session with `--shared`) and writes `results.json`; `lab/scenarios.py repro NAME DIR` sets one up and leaves it running; `lab/report.py` builds the vanilla-vs-Splint table. Run it with and without the agent; the patched run must keep the world update alive in every scenario and lose no animals.
- Expected results come from the bug report, the user's request and vanilla's measured behaviour, never from Splint's own output.
- The lab never accepts the game's terms itself; it copies the acceptance from `~/Zomboid/options.ini`. Everything it creates lives under `.lab/` (ignored by Git). Stop stray sessions with `lab/splintlab.py stop <evidence-dir>`.
