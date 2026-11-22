#!/usr/bin/env python3
"""Animal-failure scenarios for the Splint lab, run against vanilla or a patched server.

    scenarios.py run EVIDENCE_ROOT [--agent JAR] [--only NAME ...] [--keep]

Each scenario gets its own disposable session (some of them wreck the world on purpose).
A scenario stages a situation with the fixture mods, lets the real game code run, and
records what happened: exceptions grouped by message, whether the server's Lua tick kept
running, whether the connected player's server-side stats kept changing, and animal counts
before and after. Expected outcomes for the *patched* server come from the bug report and
the request ("the world must keep running, no animal may silently vanish, no duplicate
IDs"); vanilla outcomes are recorded as evidence, not asserted.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

LAB = Path(__file__).resolve().parent
sys.path.insert(0, str(LAB))
import splintlab  # noqa: E402

SPAWN = (10819, 9437)          # default Muldraugh spawn, open ground
LAKE_SHORE = (7347, 8334)      # Fallas Lake: land at x<=7350, water from x=7351
LAKE_WATER = (7352, 8334)


@dataclass
class Session:
    evidence: Path
    proc: subprocess.Popen
    console: Path = field(init=False)

    def __post_init__(self):
        self.console = self.evidence / "server-cache" / "server-console.txt"

    def server(self, line: str, timeout: float = 60) -> str:
        out = splintlab.send(self.evidence, "server", line, timeout)
        return out.split(" ACK ", 1)[1].split(" ", 1)[1] if " ACK " in out else out

    def client(self, line: str, timeout: float = 60) -> str:
        out = splintlab.send(self.evidence, "client", line, timeout)
        return out.split(" ACK ", 1)[1].split(" ", 1)[1] if " ACK " in out else out

    def lua(self, code: str, timeout: float = 60) -> str:
        return self.server("lua " + code, timeout)

    def console_cmd(self, line: str) -> None:
        with (self.evidence / "console-in.txt").open("a") as f:
            f.write(line + "\n")

    def log_size(self) -> int:
        return self.console.stat().st_size

    def log_since(self, offset: int) -> str:
        with self.console.open("r", errors="replace") as f:
            f.seek(offset)
            return f.read()

    def tp(self, x: int, y: int, settle: float = 10) -> None:
        self.client(f"tp {x} {y} 0")
        time.sleep(settle)


def exceptions(text: str) -> dict[str, int]:
    """Exception messages in a server log excerpt, grouped and counted."""
    counts: dict[str, int] = {}
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if "Exception thrown" in line or "[SPLINT]" in line and " ERROR " in line:
            msg = lines[i + 1].strip() if i + 1 < len(lines) else line
            msg = re.sub(r"\s+at \S+\(\S+\)\.$", "", msg)
            counts[msg] = counts.get(msg, 0) + 1
    return counts


def heartbeat_alive(s: Session, wait: float = 12) -> bool:
    """True when the server's Lua OnTick (which runs inside the world update) still advances."""
    offset = s.log_size()
    time.sleep(wait)
    return "[SPLAB-S] HB" in s.log_since(offset)


def stats_frozen(s: Session, wait: float = 15) -> bool:
    """True when the player's server-replicated hunger and thirst stop changing."""
    a = s.client("status")
    time.sleep(wait)
    b = s.client("status")
    grab = lambda t: re.findall(r"(hunger|thirst)=([0-9.]+)", t)
    return grab(a) == grab(b)


def census(s: Session, x: int, y: int, r: int = 30) -> str:
    return s.lua(f"return SplintLabServer.census({x},{y},0,{r})")


def count(c: str) -> int:
    m = re.search(r"onSquares=(\d+)", c)
    return int(m.group(1)) if m else -1


def ghosts(c: str) -> int:
    m = re.search(r"ghosts=(\d+)", c)
    return int(m.group(1)) if m else -1


def force_growth(s: Session, x: int, y: int, r: int, days: int = 400) -> str:
    return s.lua(force_growth_code(x, y, r, days))


def force_growth_code(x: int, y: int, r: int, days: int = 400) -> str:
    return (
        f"local n=0 for _,a in ipairs(SplintLabServer.animalsOnSquares({x},{y},0,{r})) do "
        f"local d=a:getData() if d and a:getAnimalType() then local st=d:getGrowStage() "
        f"if st and st:size()>0 then a:setHoursSurvived({days}*24) d:setAge({days}) n=n+1 end end end return n")


# --- scenarios ---------------------------------------------------------------------------

def mass_growth(s: Session) -> dict:
    """Hundreds of juveniles cross their growth age together, as a big farm does after a
    catch-up. Random 0-9999 IDs make duplicate pairs likely at this size."""
    x, y = SPAWN
    s.server("spawn chick - 300 4 0 10 110")
    s.server("spawn rabkitten - 100 -12 0 6 110")
    s.server("spawn piglet - 60 0 12 6 130")
    before = census(s, x, y, 40)
    off = s.log_size()
    grown = force_growth(s, x, y, 40)
    time.sleep(25)
    after = census(s, x, y, 40)
    return {"before": before, "forced": grown, "after": after,
            "lost": count(before) - count(after), "exceptions": exceptions(s.log_since(off)),
            "lua_alive": heartbeat_alive(s)}


def water_seed(s: Session) -> dict:
    """One animal created on a water square (the constructor's early return)."""
    s.tp(*LAKE_SHORE)
    off = s.log_size()
    made = s.lua(f"local d=AnimalDefinitions.getDef('hen') local a=addAnimal(getCell(),{LAKE_WATER[0]},{LAKE_WATER[1]},0,'hen',d:getRandomBreed()) "
                 "return 'id='..a:getAnimalID()..' data='..tostring(a:getData())")
    time.sleep(15)
    out = {"created": made, "lua_alive": heartbeat_alive(s), "stats_frozen": stats_frozen(s)}
    out["exceptions"] = exceptions(s.log_since(off))
    return out


def grow_near_ghost(s: Session) -> dict:
    """The reported stack: a juvenile grows within 4 tiles of an un-initialised animal.
    The juveniles are created first so the scheduler reaches them before the ghost."""
    s.tp(*LAKE_SHORE)
    s.server("spawn chick - 12 3 0 0 0")   # young chicks on the last land column, 2 tiles from the water square
    before = census(s, LAKE_SHORE[0], LAKE_SHORE[1], 12)
    off = s.log_size()
    # Ghost and growth in the same tick, so the chicks (earlier in the update list) grow next frame.
    s.lua(f"local d=AnimalDefinitions.getDef('hen') addAnimal(getCell(),{LAKE_WATER[0]},{LAKE_WATER[1]},0,'hen',d:getRandomBreed()) "
          + force_growth_code(LAKE_SHORE[0], LAKE_SHORE[1], 8))
    time.sleep(20)
    out = {"before": before, "lua_alive": heartbeat_alive(s), "stats_frozen": stats_frozen(s)}
    if out["lua_alive"]:
        out["after"] = census(s, LAKE_SHORE[0], LAKE_SHORE[1], 12)
    out["exceptions"] = exceptions(s.log_since(off))
    return out


def invalid_type(s: Session) -> dict:
    """An animal whose type has no definition (for example a removed animal mod)."""
    off = s.log_size()
    made = s.lua(f"local d=AnimalDefinitions.getDef('hen') local a=addAnimal(getCell(),{SPAWN[0]+3},{SPAWN[1]},0,'splintNoSuchAnimal',d:getRandomBreed()) "
                 "return 'id='..a:getAnimalID()..' data='..tostring(a:getData())")
    time.sleep(15)
    out = {"created": made, "lua_alive": heartbeat_alive(s), "stats_frozen": stats_frozen(s)}
    out["exceptions"] = exceptions(s.log_since(off))
    return out


def duplicate_ids(s: Session) -> dict:
    """Two animals sharing an ID within 4 tiles, one of them growing (vanilla deletes both)."""
    x, y = SPAWN
    s.server("spawn chick - 1 3 0 0 0")
    s.server("spawn hen - 1 4 0 0 30")
    before = census(s, x, y, 8)
    off = s.log_size()
    pair = s.lua(f"local t=SplintLabServer.animalsOnSquares({x},{y},0,8) local c,h for _,a in ipairs(t) do "
                 "if a:getAnimalType()=='chick' then c=a elseif a:getAnimalType()=='hen' then h=a end end "
                 "h:setAnimalID(c:getAnimalID()) c:setHoursSurvived(400*24) c:getData():setAge(400) "
                 "return c:getAnimalID()..'='..h:getAnimalID()")
    time.sleep(15)
    after = census(s, x, y, 8)
    return {"pair": pair, "before": before, "after": after, "lost": count(before) - count(after),
            "exceptions": exceptions(s.log_since(off))}


def unload_with_ghost(s: Session) -> dict:
    """Leave the area of an un-initialised animal so its chunk unloads, then come back."""
    s.tp(*LAKE_SHORE)
    s.lua(f"local d=AnimalDefinitions.getDef('hen') addAnimal(getCell(),{LAKE_WATER[0]},{LAKE_WATER[1]},0,'hen',d:getRandomBreed()) return 1")
    time.sleep(5)
    off = s.log_size()
    s.tp(*SPAWN, settle=60)
    mid = {"lua_alive": heartbeat_alive(s)}
    s.tp(*LAKE_SHORE, settle=30)
    return {"away": mid, "back_lua_alive": heartbeat_alive(s), "stats_frozen": stats_frozen(s),
            "exceptions": exceptions(s.log_since(off))}


def console_reply(s: Session, line: str, wait: float = 6) -> str:
    """Runs a server console command and returns what the server printed after it."""
    off = s.log_size()
    s.console_cmd(line)
    time.sleep(wait)
    text = s.log_since(off)
    keep = [l for l in text.splitlines() if "[SPLAB-S] HB" not in l]
    return "\n".join(keep)


def admin_commands(s: Session) -> dict:
    """Every /splint command against animals on the map, in a hutch, in a trailer and in an
    inventory. Expected counts come from what this scenario places, not from Splint."""
    x, y = SPAWN
    out: dict = {}
    s.server("spawn hen - 20 6 0 3 30")
    setup = s.lua(
        f"local p=SplintLabServer.player() local px,py=math.floor(p:getX()),math.floor(p:getY()) "
        # hutch with three chicks inside
        "local def=HutchDefinitions.hutchs['hutchhen'] local sq=getCell():getGridSquare(px-6,py+6,0) "
        "local h=IsoHutch.new(sq,true,def.baseSprite,def,nil) h:transmitCompleteItemToClients() "
        "local cd=AnimalDefinitions.getDef('chick') local inside=0 "
        "for i=0,2 do local a=addAnimal(getCell(),px-6,py+6,0,'chick',cd:getRandomBreed()) "
        "a:getData():setPreferredHutchPosition(i) if h:addAnimalInside(a) then inside=inside+1 end end "
        # livestock trailer with two animals
        "local tsq=getCell():getGridSquare(px+8,py-8,0) local v=addVehicleDebug('Base.Trailer_Livestock',IsoDirections.N,nil,tsq) "
        "local trailer=0 if v then for i=1,2 do local a=addAnimal(getCell(),px+8,py-8,0,'hen',AnimalDefinitions.getDef('hen'):getRandomBreed()) "
        "v:addAnimalInTrailer(a) trailer=trailer+1 end end "
        # one chick carried in the player's inventory
        "local c=addAnimal(getCell(),px,py,0,'chick',cd:getRandomBreed()) local item=instanceItem('Base.Animal') "
        "item:setAnimal(c) c:removeFromWorld() c:removeFromSquare() p:getInventory():AddItem(item) "
        "return 'inside='..inside..' trailer='..trailer..' vehicle='..(v and v:getId() or -1)..' user='..p:getUsername()")
    out["setup"] = setup
    vid = re.search(r"vehicle=(-?\d+)", setup).group(1)
    user = re.search(r"user=(\S+)", setup).group(1)
    dup = s.lua(f"local t=SplintLabServer.animalsOnSquares({x},{y},0,20) local a,b for _,an in ipairs(t) do "
                "if an:getAnimalType()=='hen' then if not a then a=an elseif not b then b=an end end end "
                "b:setAnimalID(a:getAnimalID()) return a:getAnimalID()")
    out["duplicated_id"] = dup
    time.sleep(3)
    r = 40
    for name, cmd in [
        ("status", "splint status"),
        ("help", "splint help"),
        ("census", f"splint animals census near {x} {y} {r}"),
        ("find", f"splint animals find {dup}"),
        ("fixids_dry", "splint animals fixids"),
        ("fixids", "splint animals fixids confirm"),
        ("census_after_fix", f"splint animals census near {x} {y} {r}"),
        ("kill_hutch_dry", f"splint animals kill hutches cell {x // 256} {y // 256}"),
        ("kill_hutch", f"splint animals kill hutches cell {x // 256} {y // 256} confirm"),
        ("kill_trailer", f"splint animals kill trailer {vid} confirm"),
        ("kill_inventory", f"splint animals kill inventory {user} confirm"),
        ("kill_type_dry", f"splint animals kill near {x} {y} {r} hen"),
        ("kill_type", f"splint animals kill near {x} {y} {r} hen confirm"),
        ("census_final", f"splint animals census near {x} {y} {r}"),
        ("ghosts", "splint animals kill ghosts"),
        ("frozen", "splint animals frozen"),
        ("bad_usage", "splint animals kill cell nope"),
    ]:
        out[name] = console_reply(s, cmd)
    out["lua_alive"] = heartbeat_alive(s)
    out["splint_errors"] = [l for l in s.log_since(0).splitlines() if "[Splint] ERROR" in l][:20]
    return out


INVENTORY_ANIMALS = ("local p=SplintLabServer.player() local items=p:getInventory():getAllTypeRecurse('Animal') local out={} "
                     "for i=0,items:size()-1 do local a=items:get(i):getAnimal() "
                     "out[#out+1]=(a and (tostring(a:getAnimalType())..'#'..a:getAnimalID()) or 'nil') end "
                     "return 'items='..items:size()..' '..table.concat(out,',')")


def inventory_growth(s: Session) -> dict:
    """A chick carried in the player's inventory reaches its growth age (the report's
    'ghost chicken in my pocket' theory)."""
    x, y = SPAWN
    s.lua("local p=SplintLabServer.player() local cd=AnimalDefinitions.getDef('chick') "
          "local c=addAnimal(getCell(),math.floor(p:getX())+2,math.floor(p:getY()),0,'chick',cd:getRandomBreed()) "
          "local item=instanceItem('Base.Animal') item:setAnimal(c) c:removeFromWorld() c:removeFromSquare() "
          "p:getInventory():AddItem(item) SplintLabServer.pocket=c return c:getAnimalID()")
    before = {"inventory": s.lua(INVENTORY_ANIMALS), "world": census(s, x, y, 20)}
    off = s.log_size()
    s.lua("local c=SplintLabServer.pocket c:setHoursSurvived(400*24) c:getData():setAge(400) return 1")
    time.sleep(20)
    after = {"inventory": s.lua(INVENTORY_ANIMALS), "world": census(s, x, y, 20)}
    return {"before": before, "after": after, "exceptions": exceptions(s.log_since(off)), "lua_alive": heartbeat_alive(s)}


def trailer_growth(s: Session) -> dict:
    """Chicks riding in a livestock trailer reach their growth age."""
    x, y = SPAWN
    made = s.lua(f"local tsq=getCell():getGridSquare({x}+8,{y}-8,0) local v=addVehicleDebug('Base.Trailer_Livestock',IsoDirections.N,nil,tsq) "
                 "SplintLabServer.trailer=v local cd=AnimalDefinitions.getDef('chick') "
                 f"for i=1,4 do local a=addAnimal(getCell(),{x}+8,{y}-8,0,'chick',cd:getRandomBreed()) v:addAnimalInTrailer(a) end "
                 "return v:getAnimals():size()")
    listing = ("local v=SplintLabServer.trailer local out={} for i=0,v:getAnimals():size()-1 do local a=v:getAnimals():get(i) "
               "out[#out+1]=tostring(a:getAnimalType())..'#'..a:getAnimalID() end return #out..' '..table.concat(out,',')")
    before = {"trailer": s.lua(listing), "world": census(s, x, y, 20)}
    off = s.log_size()
    s.lua("local v=SplintLabServer.trailer for i=0,v:getAnimals():size()-1 do local a=v:getAnimals():get(i) "
          "a:setHoursSurvived(400*24) a:getData():setAge(400) end return 1")
    time.sleep(20)
    after = {"trailer": s.lua(listing), "world": census(s, x, y, 20)}
    return {"made": made, "before": before, "after": after, "exceptions": exceptions(s.log_since(off)),
            "lua_alive": heartbeat_alive(s)}


def hutch_growth(s: Session) -> dict:
    """Chicks inside a hutch pass their growth age, then the hutch releases them."""
    x, y = SPAWN
    s.lua(f"local def=HutchDefinitions.hutchs['hutchhen'] local sq=getCell():getGridSquare({x}-6,{y}+6,0) "
          "local h=IsoHutch.new(sq,true,def.baseSprite,def,nil) h:transmitCompleteItemToClients() SplintLabServer.hutch=h "
          "local cd=AnimalDefinitions.getDef('chick') "
          f"for i=0,3 do local a=addAnimal(getCell(),{x}-6,{y}+6,0,'chick',cd:getRandomBreed()) a:getData():setPreferredHutchPosition(i) h:addAnimalInside(a) end "
          "return h:getAnimalInside():size()")
    listing = ("local h=SplintLabServer.hutch local out={} for _,a in pairs(SplintLabServer.inside()) do "
               "out[#out+1]=tostring(a:getAnimalType())..'#'..a:getAnimalID() end return #out..' '..table.concat(out,',')")
    s.lua("function SplintLabServer.inside() local t={} local m=SplintLabServer.hutch:getAnimalInside() local it=m:values():iterator() "
          "while it:hasNext() do t[#t+1]=it:next() end return t end return 1")
    before = {"hutch": s.lua(listing), "world": census(s, x, y, 25)}
    off = s.log_size()
    s.lua("for _,a in ipairs(SplintLabServer.inside()) do a:setHoursSurvived(400*24) a:getData():setAge(400) end return 1")
    time.sleep(20)
    inside_after = {"hutch": s.lua(listing), "world": census(s, x, y, 25)}
    s.lua("SplintLabServer.hutch:releaseAllAnimals() return 1")
    time.sleep(20)
    released = {"hutch": s.lua(listing), "world": census(s, x, y, 25)}
    return {"before": before, "inside_after_growth_age": inside_after, "after_release": released,
            "exceptions": exceptions(s.log_since(off)), "lua_alive": heartbeat_alive(s)}


SCENARIOS = {f.__name__.replace("_", "-"): f for f in
             (mass_growth, duplicate_ids, invalid_type, water_seed, grow_near_ghost, unload_with_ghost)}
# Needs the agent; run with --only admin-commands.
EXTRA = {"admin-commands": admin_commands}
# Storage-place growth investigations.
EXTRA.update({f.__name__.replace('_', '-'): f for f in (inventory_growth, trailer_growth, hutch_growth)})


def start(evidence: Path, agent: str | None) -> Session:
    cmd = [sys.executable, str(LAB / "splintlab.py"), "start", str(evidence)]
    if agent:
        cmd += ["--agent", agent]
    evidence.mkdir(parents=True, exist_ok=True)
    proc = subprocess.Popen(cmd, stdout=(evidence.parent / f"{evidence.name}.owner.log").open("w"),
                            stderr=subprocess.STDOUT)
    s = Session(evidence, proc)
    deadline = time.time() + 3600
    while True:
        text = (evidence / "client-cache" / "console.txt")
        if text.exists() and "[SPLAB] READY" in text.read_text(errors="replace"):
            break
        if proc.poll() is not None or time.time() > deadline:
            raise SystemExit(f"session failed to start: {evidence}")
        time.sleep(3)
    time.sleep(5)
    return s


def main() -> None:
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("root")
    r.add_argument("--agent")
    r.add_argument("--only", nargs="*")
    a = p.parse_args()
    root = Path(a.root).resolve()
    root.mkdir(parents=True, exist_ok=True)
    results = {}
    for name, fn in {**SCENARIOS, **EXTRA}.items():
        if (a.only and name not in a.only) or (not a.only and name in EXTRA):
            continue
        evidence = root / name
        print(f"== {name}", flush=True)
        s = start(evidence, a.agent)
        try:
            results[name] = fn(s)
        except SystemExit as e:
            results[name] = {"harness_error": str(e)}
        finally:
            subprocess.run([sys.executable, str(LAB / "splintlab.py"), "stop", str(evidence)])
            s.proc.wait(timeout=60)
        print(json.dumps(results[name], indent=2), flush=True)
        (root / "results.json").write_text(json.dumps(results, indent=2) + "\n")


if __name__ == "__main__":
    main()
