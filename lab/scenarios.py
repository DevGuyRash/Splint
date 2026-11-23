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


def here(s: Session) -> tuple[int, int]:
    """The player's square: sessions do not always spawn at exactly the same point."""
    xy = s.lua("local p=SplintLabServer.player() return math.floor(p:getX())..' '..math.floor(p:getY())")
    x, y = xy.split()[-2:]
    return int(x), int(y)


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


def fresh_spot(s: Session, dx: int) -> tuple[int, int]:
    """Moves the player to an untouched spot near spawn, so counts are not mixed up with
    animals an earlier scenario left behind in the same session."""
    x, y = here_spawn(s)
    s.tp(x + dx, y, settle=15)
    return here(s)


def tick_rate(s: Session, window: float = 20) -> float:
    """Server world-update ticks per second, from the fixture's heartbeat (0 when stopped)."""
    off = s.log_size()
    time.sleep(window)
    ticks = [int(m) for m in re.findall(r"\[SPLAB-S\] HB (\d+)", s.log_since(off))]
    if len(ticks) < 2:
        return 0.0
    return round((ticks[-1] - ticks[0]) / (5.0 * (len(ticks) - 1)), 2)


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
    catch-up. Random 0-9999 IDs make duplicate pairs likely at this size. Only farm species:
    wild ones (rabbits) flee from players and despawn on their own, which would read as loss."""
    x, y = here(s)
    s.server("spawn chick - 300 4 0 10 110")
    s.server("spawn lamb - 50 -12 0 6 110")
    s.server("spawn turkeypoult - 50 -12 8 6 110")
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
    x, y = here(s)
    off = s.log_size()
    made = s.lua(f"local d=AnimalDefinitions.getDef('hen') local a=addAnimal(getCell(),{x}+3,{y},0,'splintNoSuchAnimal',d:getRandomBreed()) "
                 "return 'id='..a:getAnimalID()..' data='..tostring(a:getData())")
    time.sleep(15)
    out = {"created": made, "lua_alive": heartbeat_alive(s), "stats_frozen": stats_frozen(s)}
    out["exceptions"] = exceptions(s.log_since(off))
    return out


def duplicate_ids(s: Session) -> dict:
    """Two animals sharing an ID within 4 tiles, one of them growing (vanilla deletes both)."""
    x, y = fresh_spot(s, 120)
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
    """Every /splint command, with combined filters, against animals on the map, in a hutch,
    in a trailer and in an inventory. Expected counts follow from what this scenario places."""
    x, y = fresh_spot(s, 240)
    out: dict = {}
    s.server("spawn hen - 20 6 0 3 30")
    s.server("spawn chick - 5 -6 0 2 5")
    setup = s.lua(
        "local p=SplintLabServer.player() local px,py=math.floor(p:getX()),math.floor(p:getY()) "
        "local def=HutchDefinitions.hutchs['hutchhen'] local sq=getCell():getGridSquare(px-6,py+6,0) "
        "local h=IsoHutch.new(sq,true,def.baseSprite,def,nil) h:transmitCompleteItemToClients() "
        "local cd=AnimalDefinitions.getDef('chick') local inside=0 "
        "for i=0,2 do local a=addAnimal(getCell(),0,0,0,'chick',cd:getRandomBreed()) "
        "a:getData():setPreferredHutchPosition(i) if h:addAnimalInside(a) then inside=inside+1 end end "
        "local tsq=getCell():getGridSquare(px+8,py-8,0) local v=addVehicleDebug('Base.Trailer_Livestock',IsoDirections.N,nil,tsq) "
        "local trailer=0 if v then for i=1,2 do local a=addAnimal(getCell(),px+8,py-8,0,'hen',AnimalDefinitions.getDef('hen'):getRandomBreed()) "
        "v:addAnimalInTrailer(a) trailer=trailer+1 end end "
        "local c=addAnimal(getCell(),0,0,0,'chick',cd:getRandomBreed()) local item=instanceItem('Base.Animal') "
        "item:setAnimal(c) p:getInventory():AddItem(item) "
        "return 'inside='..inside..' trailer='..trailer..' vehicle='..(v and v:getId() or -1)..' user='..p:getUsername()")
    out["setup"] = setup
    vid = re.search(r"vehicle=(-?\d+)", setup).group(1)
    user = re.search(r"user=(\S+)", setup).group(1)
    dup = s.lua(f"local t=SplintLabServer.animalsOnSquares({x},{y},0,20) local a,b for _,an in ipairs(t) do "
                "if an:getAnimalType()=='hen' then if not a then a=an elseif not b then b=an end end end "
                "b:setAnimalID(a:getAnimalID()) return a:getAnimalID()").split()[-1]
    time.sleep(3)
    r, cx, cy = 40, x // 256, y // 256
    near = f"near {x} {y} {r}"
    steps = [
        # (name, command, text that must appear in the reply)
        ("status", "splint status", "15 of 15 patches applied, none missing"),
        ("help", "splint help", "SELECTION terms combine"),
        ("census", f"splint animals census {near}", "31 animals {WORLD=25, HUTCH=3, TRAILER=2, INVENTORY=1}, babies 9"),
        ("census_dupes", f"splint animals census {near}", "duplicate-ID groups 1"),
        ("players", "splint players", f"{user} at"),
        ("find", f"splint animals find {dup}", "WARNING: 2 animals share this id"),
        ("list_babies_world", f"splint animals list {near} stage baby in world", "5 animals"),
        ("fixids_dry", f"splint animals fixids {near}", "Dry run: 1 selected animals"),
        ("fixids", f"splint animals fixids {near} confirm", "Gave 1 animals fresh unique IDs"),
        ("census_after_fix", f"splint animals census {near}", "duplicate-ID groups 0"),
        ("kill_babies_not_hutch_dry", f"splint animals kill {near} type chicken stage baby except hutch", "would remove 5 animals"),
        ("kill_hutch_babies", f"splint animals kill {near} type chicken stage baby in hutch confirm", "Removed 3 of 3"),
        ("kill_world_chicks", f"splint animals kill {near} type chick confirm", "Removed 5 of 5"),
        ("kill_inventory", f"splint animals kill inventory {user} confirm", "Removed 1 of 1"),
        ("kill_trailer", f"splint animals kill trailer {vid} confirm", "Removed 2 of 2"),
        ("kill_limit", f"splint animals kill cell {cx} {cy} type hen sex female limit 5 confirm", "Removed 5 of 5"),
        ("census_final", f"splint animals census {near}", "15 animals {WORLD=15}"),
        ("problems", "splint problems 5", "Animal problems handled since start"),
        ("bad_usage", "splint animals kill cell nope", "Not a number"),
        ("bad_term", "splint animals kill near 1 2 3 colour blue", "Unknown term 'colour'"),
    ]
    checks = {}
    for name, cmd, expect in steps:
        reply = console_reply(s, cmd)
        out[name] = reply
        checks[name] = expect in reply
    out["checks"] = checks
    out["all_checks_pass"] = all(checks.values())
    out["lua_alive"] = heartbeat_alive(s)
    return out

INVENTORY_ANIMALS = ("local p=SplintLabServer.player() local items=p:getInventory():getAllTypeRecurse('Animal') local out={} "
                     "for i=0,items:size()-1 do local a=items:get(i):getAnimal() "
                     "out[#out+1]=(a and (tostring(a:getAnimalType())..'#'..a:getAnimalID()) or 'nil') end "
                     "return 'items='..items:size()..' '..table.concat(out,',')")


def inventory_growth(s: Session) -> dict:
    """A chick carried in the player's inventory reaches its growth age (the report's
    'ghost chicken in my pocket' theory)."""
    x, y = fresh_spot(s, -120)
    s.lua("local p=SplintLabServer.player() local cd=AnimalDefinitions.getDef('chick') "
          "local c=addAnimal(getCell(),math.floor(p:getX())+2,math.floor(p:getY()),0,'chick',cd:getRandomBreed()) c:addToWorld() "
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
    x, y = fresh_spot(s, -240)
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
    x, y = fresh_spot(s, 360)
    s.lua(f"local def=HutchDefinitions.hutchs['hutchhen'] local sq=getCell():getGridSquare({x}-6,{y}+6,0) "
          "local h=IsoHutch.new(sq,true,def.baseSprite,def,nil) h:transmitCompleteItemToClients() SplintLabServer.hutch=h "
          "local cd=AnimalDefinitions.getDef('chick') "
          "for i=0,3 do local a=addAnimal(getCell(),0,0,0,'chick',cd:getRandomBreed()) a:getData():setPreferredHutchPosition(i) h:addAnimalInside(a) end "
          "return h:getAnimalInside():size()")
    listing = ("local out={} for _,a in ipairs(SplintLabServer.inside()) do "
               "out[#out+1]=tostring(a:getAnimalType())..'#'..a:getAnimalID() end return #out..' '..table.concat(out,',')")
    s.lua("function SplintLabServer.inside() local t={} local h=SplintLabServer.hutch "
          "for i=0,h:getMaxAnimals()-1 do local a=h:getAnimal(i) if a then t[#t+1]=a end end return t end return 1")
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


def grow_near_invalid_ghost(s: Session) -> dict:
    """The report's exact stack with a seed Splint cannot prevent: an animal of an undefined
    type next to juveniles that reach their growth age in the same tick."""
    x, y = fresh_spot(s, 480)
    s.server("spawn chick - 12 2 0 1 0")
    before = census(s, x, y, 12)
    off = s.log_size()
    s.lua(f"local d=AnimalDefinitions.getDef('hen') addAnimal(getCell(),{x}+3,{y},0,'splintNoSuchAnimal',d:getRandomBreed()) "
          + force_growth_code(x, y, 8))
    time.sleep(20)
    out = {"before": before, "lua_alive": heartbeat_alive(s), "stats_frozen": stats_frozen(s)}
    if out["lua_alive"]:
        out["after"] = census(s, x, y, 12)
    out["exceptions"] = exceptions(s.log_since(off))
    return out


def malformed_breed(s: Session) -> dict:
    """A fully initialised animal whose data is damaged (null breed, as in reports of
    malformed animals from mods or saves). Its growth throws on every update."""
    x, y = fresh_spot(s, 600)
    s.server("spawn chick - 3 3 0 1 0")
    before = census(s, x, y, 10)
    off = s.log_size()
    s.lua(f"local t=SplintLabServer.animalsOnSquares({x},{y},0,10) local a=t[1] a:getData():setBreed(nil) "
          "a:setHoursSurvived(400*24) a:getData():setAge(400) SplintLabServer.broken=a return a:getAnimalID()")
    time.sleep(25)
    out = {"before": before, "lua_alive": heartbeat_alive(s), "stats_frozen": stats_frozen(s)}
    if out["lua_alive"]:
        out["after"] = census(s, x, y, 10)
        if json.loads((s.evidence / "session.json").read_text()).get("agent"):
            out["frozen"] = console_reply(s, "splint animals frozen")
    out["exceptions"] = exceptions(s.log_since(off))
    return out


def stress(s: Session, total: int, spread: int, dx: int) -> dict:
    """`total` farm juveniles packed around one spot cross their growth age together, while the
    server's tick rate is measured before and after. Vanilla's 0-9999 IDs give about
    total*(total-1)/2/10000 duplicate pairs at this size (200 at 2000, 1250 at 5000)."""
    x, y = fresh_spot(s, dx)
    base_tps = tick_rate(s)
    mix = {"chick": 0.6, "piglet": 0.15, "lamb": 0.15, "turkeypoult": 0.1}
    days = {"chick": 110, "piglet": 130, "lamb": 170, "turkeypoult": 110}
    for kind, share in mix.items():
        n = int(total * share)
        while n > 0:
            batch = min(n, 500)
            s.server(f"spawn {kind} - {batch} 0 0 {spread} {days[kind]}", timeout=300)
            n -= batch
    before = census(s, x, y, spread + 3)
    loaded_tps = tick_rate(s)
    off = s.log_size()
    grown = s.lua(force_growth_code(x, y, spread + 3), timeout=300)
    time.sleep(60)
    after = census(s, x, y, spread + 3)
    return {"before": before, "forced": grown, "after": after, "lost": count(before) - count(after),
            "tps_empty": base_tps, "tps_with_animals": loaded_tps, "tps_after_growth": tick_rate(s),
            "exceptions": exceptions(s.log_since(off)), "lua_alive": heartbeat_alive(s)}


def stress_2000(s: Session) -> dict:
    return stress(s, 2000, 22, -360)


def stress_5000(s: Session) -> dict:
    return stress(s, 5000, 35, -720)


SCENARIOS = {f.__name__.replace("_", "-"): f for f in
             (mass_growth, duplicate_ids, invalid_type, water_seed, grow_near_ghost, grow_near_invalid_ghost,
              malformed_breed, unload_with_ghost)}
# Needs the agent; run with --only admin-commands.
EXTRA = {"admin-commands": admin_commands, "stress-2000": stress_2000, "stress-5000": stress_5000}
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
    if agent:
        # A patched run is only evidence if the patch is live in this JVM.
        status = console_reply(s, "splint status", wait=8)
        if "methods patched" not in status or "MISSING" in status:
            raise SystemExit(f"Splint is not active in {evidence}: {status[-400:]}")
        (evidence / "splint-status.txt").write_text(status + "\n")
    return s


def main() -> None:
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("root")
    r.add_argument("--agent")
    r.add_argument("--only", nargs="*")
    r.add_argument("--shared", action="store_true", help="run all chosen scenarios in one session")
    rp = sub.add_parser("repro", help="set up one scenario and leave the session running for inspection")
    rp.add_argument("name", choices=sorted({**SCENARIOS, **EXTRA}))
    rp.add_argument("evidence")
    rp.add_argument("--agent")
    a = p.parse_args()
    if a.cmd == "repro":
        evidence = Path(a.evidence).resolve()
        s = start(evidence, a.agent)
        result = {**SCENARIOS, **EXTRA}[a.name](s)
        print(json.dumps(result, indent=2))
        lab = LAB / "splintlab.py"
        print(f"\nSession left running: {evidence}\n"
              f"  server console:  python3 {lab} console {evidence} splint status\n"
              f"  server Lua:      python3 {lab} server {evidence} census\n"
              f"  logs:            {evidence}/server-cache/server-console.txt, {evidence}/server-cache/Logs/\n"
              f"  stop:            python3 {lab} stop {evidence}")
        return
    root = Path(a.root).resolve()
    root.mkdir(parents=True, exist_ok=True)
    every = {**SCENARIOS, **EXTRA}
    # --only keeps the given order, so a scenario that wrecks vanilla can run last in --shared.
    chosen = [(n, every[n]) for n in a.only] if a.only else list(SCENARIOS.items())
    results: dict = {}

    def record(name: str, fn, s: Session) -> None:
        print(f"== {name}", flush=True)
        try:
            results[name] = fn(s)
        except SystemExit as e:
            results[name] = {"harness_error": str(e)}
        print(json.dumps(results[name], indent=2), flush=True)
        (root / "results.json").write_text(json.dumps(results, indent=2) + "\n")

    def stop(s: Session) -> None:
        subprocess.run([sys.executable, str(LAB / "splintlab.py"), "stop", str(s.evidence)])
        s.proc.wait(timeout=60)

    if a.shared:
        # One session for every scenario: only meaningful when none of them can wreck the world.
        s = start(root / "shared", a.agent)
        here_spawn(s)
        try:
            for name, fn in chosen:
                record(name, fn, s)
                s.tp(*here_spawn(s), settle=15)
        finally:
            stop(s)
        return
    for name, fn in chosen:
        s = start(root / name, a.agent)
        try:
            record(name, fn, s)
        finally:
            stop(s)


def here_spawn(s: Session) -> tuple[int, int]:
    """Back to where the session's player first spawned, between shared scenarios."""
    if not hasattr(s, "spawn_xy"):
        s.spawn_xy = here(s)  # type: ignore[attr-defined]
    return s.spawn_xy  # type: ignore[attr-defined]


if __name__ == "__main__":
    main()
