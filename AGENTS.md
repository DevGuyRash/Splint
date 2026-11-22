# Splint

Splint is a server-side fix and admin-tools patch for Project Zomboid Build 42. Its first scope is animals: unique animal IDs, removal that always completes, per-animal failure isolation, structured logging, and `/splint` admin commands. It is meant to grow into a general fixes and quality-of-life patch, so keep each area in its own package (`splint.animals`, later `splint.<area>`) behind the shared agent, log and command plumbing.

## Keep the design

- The game only runs mod Lua in Kahlua, and the failures fixed here are in Java, outside anything Lua can intercept. So the core is a Java agent (`agent/src`) that rewrites a short, explicit list of methods in memory as the JVM loads them. It never writes game files and never ships game code.
- Every patch goes in `SplintAgent.PATCHES` and uses `Patcher`: the original body stays callable as `splint$<name>`, and the hook decides when to call it. Prefer calling the original and fixing around it over re-implementing vanilla logic.
- Each hook needs a reproduced failure behind it, recorded in `docs/investigation.md` with the lab scenario that shows it. Do not add speculative patches.
- A hook must never throw into the game loop for an animal-specific problem. Catch, log with `AnimalOps.describe` (type, ID, coordinates, map cell, apop file, container, nearest player) and continue. Rethrow `VirtualMachineError`.
- Logging goes through `splint.core.Log`; repeating problems use `Log.problem` with a stable key so they are rate limited.
- Admin commands are dry runs unless they end with `confirm`, and every destructive action is written to the Splint log with the admin's name.
- Verify every game method you call exists in the installed build (`javap -cp projectzomboid.jar`). After a game update, run `python3 build.py --verify` to see which patches no longer apply.

## Build and verify

- `python3 build.py` compiles against the installed `projectzomboid.jar` (compile-time only) and writes `dist/splint.jar`.
- `lab/splintlab.py` runs a disposable loopback multiplayer session: the steamcmd dedicated server in `.lab/server` (app 380870), an Xvfb display and a headless client from the local game install. `lab/scenarios.py run .lab/runs/<new-dir> [--agent dist/splint.jar]` runs every failure scenario in its own session and writes `results.json`. Run it with and without the agent; the patched run must keep the world update alive in every scenario and lose no animals.
- Expected results come from the bug report, the user's request and vanilla's measured behaviour, never from Splint's own output.
- The lab never accepts the game's terms itself; it copies the acceptance from `~/Zomboid/options.ini`. Everything it creates lives under `.lab/` (ignored by Git). Stop stray sessions with `lab/splintlab.py stop <evidence-dir>`.
