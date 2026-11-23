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
import os
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
    # Sessions resumed from this one's saved world (see restart()); stopped together with it.
    children: list = field(default_factory=list)
    # Server console size when the player was in the world (set by start()).
    ready_offset: int = 0

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


# Gives animal `b` the ID of animal `a` the way a pre-Splint save can contain it: through the
# original setter (Splint keeps it reachable as splint$setAnimalID), so Splint's guard on
# setAnimalID does not refuse it. On vanilla it is the normal setter.
FORCE_DUP = ("local function forceId(b, id) local raw = b['splint$setAnimalID'] "
             "if raw then raw(b, id) else b:setAnimalID(id) end end ")


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


TOTAL_ANIMALS = ("local n=0 local l=getCell():getObjectListForLua() for i=0,l:size()-1 do "
                 "if instanceof(l:get(i),'IsoAnimal') then n=n+1 end end return n")


def player_alive(s: Session) -> bool:
    return s.lua("local p=SplintLabServer.player() return tostring(p ~= nil)").endswith("true")


def _server_cpu_seconds(s: Session) -> float:
    """User+system CPU time of the dedicated server process, from /proc."""
    pid = json.loads((s.evidence / "session.json").read_text())["server_pid"]
    fields = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    return (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK")


def tick_rate(s: Session, window: float = 20) -> float:
    """Server world-update ticks per second, from the fixture's heartbeat (0 when stopped)."""
    return tick_stats(s, window)["tps"]


def tick_stats(s: Session, window: float = 20) -> dict:
    """Ticks per second (depends on machine load) and server CPU milliseconds per tick (the work
    a tick costs, much less sensitive to other processes competing for the CPU)."""
    off = s.log_size()
    cpu0 = _server_cpu_seconds(s)
    time.sleep(window)
    cpu1 = _server_cpu_seconds(s)
    ticks = [int(m) for m in re.findall(r"\[SPLAB-S\] HB (\d+)", s.log_since(off))]
    if len(ticks) < 2:
        return {"tps": 0.0, "cpu_ms_per_tick": None}
    n = ticks[-1] - ticks[0]
    span = 5.0 * (len(ticks) - 1)
    # CPU over the whole window, ticks over the heartbeat span: scale ticks to the window.
    per_tick = (cpu1 - cpu0) * 1000 / max(1.0, n * window / span)
    return {"tps": round(n / span, 2), "cpu_ms_per_tick": round(per_tick, 1)}


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


def census(s: Session, x: int, y: int, r: int = 30, timeout: float = 60) -> str:
    return s.lua(f"return SplintLabServer.census({x},{y},0,{r})", timeout)


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
    pair = s.lua(FORCE_DUP + f"local t=SplintLabServer.animalsOnSquares({x},{y},0,8) local c,h for _,a in ipairs(t) do "
                 "if a:getAnimalType()=='chick' then c=a elseif a:getAnimalType()=='hen' then h=a end end "
                 "forceId(h, c:getAnimalID()) c:setHoursSurvived(400*24) c:getData():setAge(400) "
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
    dup = s.lua(FORCE_DUP + f"local t=SplintLabServer.animalsOnSquares({x},{y},0,20) local a,b for _,an in ipairs(t) do "
                "if an:getAnimalType()=='hen' then if not a then a=an elseif not b then b=an end end end "
                "forceId(b, a:getAnimalID()) return a:getAnimalID()").split()[-1]
    # The ordinary setter must refuse to create a second duplicate (Splint only).
    out["set_duplicate_refused"] = s.lua(f"local t=SplintLabServer.animalsOnSquares({x},{y},0,20) local a,c for _,an in ipairs(t) do "
                                         "if an:getAnimalType()=='hen' and not a then a=an elseif an:getAnimalType()=='chick' and not c then c=an end end "
                                         "c:setAnimalID(a:getAnimalID()) return tostring(c:getAnimalID() ~= a:getAnimalID())")
    time.sleep(3)
    r, cx, cy = 40, x // 256, y // 256
    near = f"near {x} {y} {r}"
    steps = [
        # (name, command, text that must appear in the reply)
        ("status", "splint status", "none missing"),
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
        ("problems", "splint problems 5", "roblems handled since"),
        ("modules", "splint modules", "animals: unique animal IDs"),
        ("bad_usage", "splint animals kill cell nope", "Not a number"),
        ("bad_term", "splint animals kill near 1 2 3 colour blue", "Unknown term 'colour'"),
    ]
    checks = {}
    for name, cmd, expect in steps:
        reply = console_reply(s, cmd)
        out[name] = reply
        checks[name] = expect in reply
    checks["set_duplicate_refused"] = out["set_duplicate_refused"].endswith("true")
    out["checks"] = checks
    out["all_checks_pass"] = all(checks.values())
    out["lua_alive"] = heartbeat_alive(s)
    return out

INVENTORY_ANIMALS = ("local p=SplintLabServer.player() local items=p:getInventory():getAllTypeRecurse('Animal') local out={} "
                     "for i=0,items:size()-1 do local a=items:get(i):getAnimal() "
                     "out[#out+1]=(a and (tostring(a:getAnimalType())..'#'..a:getAnimalID()..'/age='..(a:getData() and a:getData():getAge() or -1)) or 'nil') end "
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
    # Grown rams and boars attack; a dead player would unload the area and corrupt every count.
    s.lua("local p=SplintLabServer.player() p:setGodMod(true) p:setInvincible(true) return 1")
    base = tick_stats(s)
    mix = {"chick": 0.6, "piglet": 0.15, "lamb": 0.15, "turkeypoult": 0.1}
    days = {"chick": 110, "piglet": 130, "lamb": 170, "turkeypoult": 110}
    for kind, share in mix.items():
        n = int(total * share)
        while n > 0:
            batch = min(n, 500)
            s.server(f"spawn {kind} - {batch} 0 0 {spread} {days[kind]}", timeout=300)
            n -= batch
    before = census(s, x, y, spread + 3, timeout=600)
    total_before = int(s.lua(TOTAL_ANIMALS, timeout=600).split()[-1])
    loaded = tick_stats(s)
    off = s.log_size()
    grown = s.lua(force_growth_code(x, y, spread + 3), timeout=600)
    time.sleep(60)
    after = census(s, x, y, spread + 3, timeout=600)
    total_after = int(s.lua(TOTAL_ANIMALS, timeout=600).split()[-1])
    return {"before": before, "forced": grown, "after": after,
            "animals_in_update_list_before": total_before, "animals_in_update_list_after": total_after,
            "lost": total_before - total_after, "player_alive": player_alive(s),
            "tps_empty": base["tps"], "tps_with_animals": loaded["tps"],
            "cpu_ms_per_tick_empty": base["cpu_ms_per_tick"], "cpu_ms_per_tick_with_animals": loaded["cpu_ms_per_tick"],
            **{k + "_after_growth": v for k, v in tick_stats(s).items()},
            "exceptions": exceptions(s.log_since(off)), "lua_alive": heartbeat_alive(s)}


def stress_2000(s: Session) -> dict:
    return stress(s, 2000, 22, -360)


def stress_5000(s: Session) -> dict:
    return stress(s, 5000, 35, -720)


def legacy_dup_reload(s: Session) -> dict:
    """Two hens share an ID (as in a save written before Splint), then their chunk unloads and
    loads again when the player leaves and comes back."""
    x, y = fresh_spot(s, 840)
    s.server("spawn hen - 6 3 0 1 30")
    made = s.lua(FORCE_DUP + f"local t=SplintLabServer.animalsOnSquares({x},{y},0,10) local a,b for _,an in ipairs(t) do "
                 "if an:getAnimalType()=='hen' then if not a then a=an elseif not b then b=an end end end "
                 "forceId(b, a:getAnimalID()) return a:getAnimalID()..'='..b:getAnimalID()")
    before = census(s, x, y, 10)
    off = s.log_size()
    s.tp(x + 2000, y, settle=60)          # far enough for the chunks to unload
    s.tp(x, y, settle=40)
    after = census(s, x, y, 10)
    return {"pair": made, "before": before, "after": after, "lost": count(before) - count(after),
            "exceptions": exceptions(s.log_since(off)), "lua_alive": heartbeat_alive(s)}


# --- open questions: restarts, carried animals, trailers, Ovo Farms -----------------------

OVO_FARMS = (3960, 10805)      # Ovo Farms, 1245 Payne Road, Echo Creek: three chicken ranch zones
# Lua: juvenile `a` half an in-game day short of growing, so the game's own clock makes it grow.
# The growth age comes from the running game's stage table for that animal's group and type, so
# any species, mod or rebalance is handled without numbers in this file.
NEAR_GROWTH = ("local st=AnimalDefinitions.stages[a:getAdef():getGroup()] st=st and st.stages[a:getAnimalType()] "
               "local g=st and st.ageToGrow if g then a:getData():setAge(g-1) a:setHoursSurvived((g-1)*24+12) end ")


def grows_at(s: Session, group: str, animal_type: str) -> int:
    """Days until `animal_type` grows, from the running game's own definitions."""
    return int(s.lua(f"return AnimalDefinitions.stages['{group}'].stages['{animal_type}'].ageToGrow").split()[-1])


# The time chamber: how fast the lab runs the game's clock. Day length (real minutes per in-game
# day, the sandbox DayLength setting) and the admin `settimespeed` multiplier (each tick simulates
# more time, as fast-forward does) stack. Defaults come from the time-chamber probe; override with
# SPLINT_LAB_MINUTES_PER_DAY / SPLINT_LAB_TIMESPEED.
CHAMBER_MINUTES_PER_DAY = float(os.environ.get("SPLINT_LAB_MINUTES_PER_DAY", "1"))
CHAMBER_TIMESPEED = float(os.environ.get("SPLINT_LAB_TIMESPEED", "20"))  # 7.7 in-game hours per real second, tick rate unchanged


def set_time(s: Session, minutes_per_day: float, timespeed: float) -> None:
    s.lua(f"getGameTime():setMinutesPerDay({minutes_per_day}) return 1")
    console_reply(s, f"settimespeed {timespeed:g}", wait=2)


def fast_time(s: Session, minutes_per_day: float | None = None, timespeed: float | None = None) -> float:
    """Puts the server in the time chamber and returns the world age in hours at that moment,
    so callers can report how far the clock moved. Days pass in seconds, so the player is made
    invincible first: without food or water they would die within the scenario."""
    s.lua("local p=SplintLabServer.player() if p then p:setGodMod(true) p:setInvincible(true) end return 1")
    set_time(s, CHAMBER_MINUTES_PER_DAY if minutes_per_day is None else minutes_per_day,
             CHAMBER_TIMESPEED if timespeed is None else timespeed)
    return world_hours(s)


def world_hours(s: Session, timeout: float = 60) -> float:
    return float(s.lua("return getGameTime():getWorldAgeHours()", timeout).split()[-1])


def wait_game_hours(s: Session, hours: float, timeout: float = 900) -> dict:
    """Waits until `hours` of in-game time have passed (not a fixed number of real seconds), so a
    faster chamber shortens every scenario. The chamber is on only for the wait itself: travel,
    unloading and measurements run at normal speed, so no more in-game time passes than asked
    for. Gives up after `timeout` real seconds, or as soon as the world update stops (the clock
    cannot be read once server Lua no longer runs)."""
    t0 = time.time()
    try:
        h0 = fast_time(s)
    except SystemExit:
        return {"game_hours": 0.0, "real_seconds": 0.0, "clock_readable": False}
    h = h0
    readable = True
    while time.time() - t0 < timeout:
        time.sleep(1)
        try:
            h = world_hours(s, timeout=30)
        except SystemExit:
            readable = False
            break
        if h - h0 >= hours:
            break
    if readable:
        set_time(s, 60, 1)
    return {"game_hours": round(h - h0, 1), "real_seconds": round(time.time() - t0), "clock_readable": readable}

# Every animal in the server's update list that carries `id`: type, square, whether it has data.
WHERE_ID = ("local function where(id) local l=getCell():getObjectListForLua() local out={} for i=0,l:size()-1 do "
            "local o=l:get(i) if instanceof(o,'IsoAnimal') and o:getAnimalID()==id then "
            "out[#out+1]=tostring(o:getAnimalType())..'@'..math.floor(o:getX())..','..math.floor(o:getY())"
            "..' square='..tostring(o:getCurrentSquare()~=nil)..' data='..tostring(o:getData()~=nil) end end "
            "return #out..' '..table.concat(out,';') end ")
# Half-built animals anywhere in the update list (the census only sees squares near a point).
LIST_GHOSTS = ("local n=0 local l=getCell():getObjectListForLua() for i=0,l:size()-1 do local o=l:get(i) "
               "if instanceof(o,'IsoAnimal') and (o:getAnimalID()==-1 or o:getData()==nil or o:getAnimalType()==nil) "
               "then n=n+1 end end return n")
# The first livestock trailer the server knows about (restarts lose Lua references).
TRAILER = ("local function trailer() local it=getCell():getVehicles():iterator() while it:hasNext() do local v=it:next() "
           "if string.contains(v:getScriptName(), 'Trailer_Livestock') then return v end end end ")
TRAILER_LIST = (TRAILER + "local v=trailer() if not v then return 'no trailer' end local out={} "
                "for i=0,v:getAnimals():size()-1 do local a=v:getAnimals():get(i) "
                "out[#out+1]=tostring(a:getAnimalType())..'#'..a:getAnimalID()..'/age='..(a:getData() and a:getData():getAge() or -1) end "
                "return #out..' '..table.concat(out,',')")


def restart(s: Session, agent: str | None | bool = True) -> tuple[Session, dict]:
    """Saves the world through the server's own `quit` and starts a new session on that save, as
    a restart (or a rollback to the latest save) does on a live server. The player logs back in
    as the same character. `agent=True` keeps the first session's agent; a path or None picks."""
    off = s.log_size()
    server_log = s.evidence / "server.log"
    log_off = server_log.stat().st_size
    quit_out = subprocess.run([sys.executable, str(LAB / "splintlab.py"), "quit", str(s.evidence)],
                              capture_output=True, text=True, timeout=1200).stdout.strip().splitlines()
    s.proc.wait(timeout=120)
    with server_log.open(errors="replace") as f:
        f.seek(log_off)
        stderr_tail = f.read()
    saving = {"quit": quit_out[-1] if quit_out else "unknown", "exceptions_while_saving": exceptions(s.log_since(off)),
              "worker_thread_died": "Exception in thread" in stderr_tail}
    chosen = json.loads((s.evidence / "session.json").read_text()).get("agent") if agent is True else agent
    s2 = start(s.evidence.with_name(s.evidence.name + "-resumed"), chosen or None, resume=s.evidence)
    s.children.append(s2)
    return s2, saving


def after_login(s: Session, x: int, y: int, r: int = 12) -> dict:
    """What a resumed session looks like once the player is back: is the world update running,
    where is the player, what is on the squares around (x, y), anything thrown since start."""
    out: dict = {"lua_alive": heartbeat_alive(s, wait=20)}
    if out["lua_alive"]:
        out["player_at"] = here(s)
        out["census"] = census(s, x, y, r)
        out["ghosts_in_update_list"] = s.lua(LIST_GHOSTS).split()[-1]
    # From the moment the player was back in the world: boot noise (optional mod folders, the
    # fixture polling before a player exists) is not part of the result.
    out["exceptions_since_login"] = exceptions(s.log_since(s.ready_offset))
    return out


def _ghost_then_restart(s: Session, agent: str | None | bool) -> dict:
    s.tp(*LAKE_SHORE)
    s.server("spawn hen - 6 -3 0 1 30")
    before = census(s, LAKE_SHORE[0], LAKE_SHORE[1], 12)
    off = s.log_size()
    s.lua(f"local d=AnimalDefinitions.getDef('hen') addAnimal(getCell(),{LAKE_WATER[0]},{LAKE_WATER[1]},0,'hen',d:getRandomBreed()) return 1")
    time.sleep(15)
    first = {"before": before, "lua_alive": heartbeat_alive(s), "exceptions": exceptions(s.log_since(off))}
    # The `save` command (what periodic saves run) with the ghost still in the world.
    save_off = s.log_size()
    log_off = (s.evidence / "server.log").stat().st_size
    s.console_cmd("save")
    time.sleep(90)
    with (s.evidence / "server.log").open(errors="replace") as f:
        f.seek(log_off)
        tail = f.read()
    text = s.log_since(save_off)
    # The console prints "World saved" as soon as the command is queued, before anything is
    # written; only "Saving finish" means the save completed.
    first["save_command"] = {"said_world_saved": "World saved" in text, "finished": "Saving finish" in text,
                             "exceptions": exceptions(text),
                             "save_thread_died": "Exception in thread" in tail or "Exception in thread" in text,
                             "server_still_running": Path(f"/proc/{json.loads((s.evidence / 'session.json').read_text())['server_pid']}").exists()}
    s2, saving = restart(s, agent)
    return {"first_session": first, "save_and_quit": saving,
            "resumed_agent": json.loads((s2.evidence / "session.json").read_text()).get("agent"),
            "after_restart": after_login(s2, *LAKE_SHORE)}


def ghost_save_restart(s: Session) -> dict:
    """Open question: did the rollback help by changing the save, or only by restarting? A ghost
    is in the world (world update stopped on vanilla), the server saves and quits, and the same
    world starts again: does the ghost come back, does the save break?"""
    return _ghost_then_restart(s, True)


def ghost_save_splint_resume(s: Session) -> dict:
    """A world saved by vanilla while a ghost was in it, started again with Splint installed (what
    an admin does when they install Splint on a server that already has the problem). Run without
    --agent: the second session always loads dist/splint.jar."""
    return _ghost_then_restart(s, str(LAB.parent / "dist" / "splint.jar"))


def pocket_far_growth(s: Session) -> dict:
    """Open question: where does a carried juvenile end up when it grows? The chick is picked up
    at one spot and grows after the player has gone far enough for that spot to unload."""
    x, y = fresh_spot(s, -120)
    cid = s.lua("local p=SplintLabServer.player() local cd=AnimalDefinitions.getDef('chick') "
                "local c=addAnimal(getCell(),math.floor(p:getX())+2,math.floor(p:getY()),0,'chick',cd:getRandomBreed()) c:addToWorld() "
                "local item=instanceItem('Base.Animal') item:setAnimal(c) c:removeFromWorld() c:removeFromSquare() "
                "p:getInventory():AddItem(item) SplintLabServer.pocket=c local a=c " + NEAR_GROWTH +
                "return c:getAnimalID()").split()[-1]
    s.tp(x + 2000, y, settle=60)
    off = s.log_size()
    clock = wait_game_hours(s, 60)          # 2.5 in-game days; the chick needed one
    alive = heartbeat_alive(s)
    away: dict = {"lua_alive": alive}
    if alive:
        away["clock"] = clock
    if alive:
        away.update(carried_age=s.lua("local c=SplintLabServer.pocket return 'age='..c:getData():getAge()..' hoursSurvived='"
                                      "..math.floor(c:getHoursSurvived())"),
                    inventory=s.lua(INVENTORY_ANIMALS), where=s.lua(WHERE_ID + f"return where({cid})"),
                    ghosts_in_update_list=s.lua(LIST_GHOSTS).split()[-1], player_at=here(s))
    s.tp(x, y, settle=40)
    back: dict = {"lua_alive": heartbeat_alive(s)}
    if back["lua_alive"]:
        back.update(where=s.lua(WHERE_ID + f"return where({cid})"), census_at_pickup=census(s, x + 2, y, 10))
    return {"chick_id": cid, "picked_up_at": [x + 2, y], "grew_while_away": away, "back_at_pickup": back,
            "exceptions": exceptions(s.log_since(off))}


def pocket_relog(s: Session) -> dict:
    """The player logs out with a chick in their pocket and logs back in (here: the server
    restarts and the player reconnects), then the chick reaches its growth age."""
    x, y = fresh_spot(s, -120)
    cid = s.lua("local p=SplintLabServer.player() local cd=AnimalDefinitions.getDef('chick') "
                "local c=addAnimal(getCell(),math.floor(p:getX())+2,math.floor(p:getY()),0,'chick',cd:getRandomBreed()) c:addToWorld() "
                "local item=instanceItem('Base.Animal') item:setAnimal(c) c:removeFromWorld() c:removeFromSquare() "
                "p:getInventory():AddItem(item) local a=c " + NEAR_GROWTH + "return c:getAnimalID()").split()[-1]
    before = s.lua(INVENTORY_ANIMALS)
    s2, saving = restart(s)
    login = after_login(s2, x, y)
    out = {"chick_id": cid, "before": before, "save_and_quit": saving, "after_login": login}
    if login["lua_alive"]:
        out["inventory_after_login"] = s2.lua(INVENTORY_ANIMALS)
        out["carried_animal_after_login"] = s2.lua(
            "local p=SplintLabServer.player() local items=p:getInventory():getAllTypeRecurse('Animal') "
            "if items:size()==0 then return 'none' end local a=items:get(0):getAnimal() if not a then return 'nil animal' end "
            "return 'at='..math.floor(a:getX())..','..math.floor(a:getY())..','..math.floor(a:getZ())..' age='..a:getData():getAge()"
            "..' container='..tostring(a:getContainer()~=nil)")
        off = s2.log_size()
        clock = wait_game_hours(s2, 60)     # 2.5 in-game days; the chick needed one
        grown: dict = {"lua_alive": heartbeat_alive(s2)}
        if grown["lua_alive"]:
            grown.update(clock=clock, inventory=s2.lua(INVENTORY_ANIMALS),
                         where=s2.lua(WHERE_ID + f"return where({cid})"),
                         ghosts_in_update_list=s2.lua(LIST_GHOSTS).split()[-1])
        grown["exceptions"] = exceptions(s2.log_since(off))
        out["grew_after_login"] = grown
        # Carried animals do not age, so a chick only grows in a pocket when it is already past its
        # growth age (a hutch keeps chicks as chicks past it). After a relog the carried animal
        # has no container, which changes grow()'s path: put it past its growth age and watch.
        off = s2.log_size()
        set_time(s2, 60, 1)
        s2.lua("local p=SplintLabServer.player() local items=p:getInventory():getAllTypeRecurse('Animal') "
               "for i=0,items:size()-1 do local a=items:get(i):getAnimal() if a then a:setHoursSurvived(400*24) a:getData():setAge(400) end end return 1")
        time.sleep(30)
        over: dict = {"lua_alive": heartbeat_alive(s2), "stats_frozen": stats_frozen(s2)}
        if over["lua_alive"]:
            over.update(inventory=s2.lua(INVENTORY_ANIMALS), where=s2.lua(WHERE_ID + f"return where({cid})"),
                        animals_in_update_list=s2.lua(TOTAL_ANIMALS).split()[-1],
                        ghosts_in_update_list=s2.lua(LIST_GHOSTS).split()[-1])
            time.sleep(30)
            over.update(where_30s_later=s2.lua(WHERE_ID + f"return where({cid})"),
                        animals_in_update_list_30s_later=s2.lua(TOTAL_ANIMALS).split()[-1])
        over["exceptions"] = exceptions(s2.log_since(off))
        out["past_growth_age_after_login"] = over
    return out


def carcass_relog(s: Session) -> dict:
    """'I butchered some chickens before I logged ... possibly a ghost chicken in my pocket': the
    player carries chicken carcasses (as picked up from the ground) when they log out and back in."""
    x, y = fresh_spot(s, 600)
    s.server("spawn hen - 3 2 0 0 30")
    killed = s.lua(f"local n=0 for _,a in ipairs(SplintLabServer.animalsOnSquares({x},{y},0,6)) do a:setHealth(0) n=n+1 end return n")
    time.sleep(8)
    carried = s.lua(f"local p=SplintLabServer.player() local n=0 for dx=-6,6 do for dy=-6,6 do "
                    f"local sq=getCell():getGridSquare({x}+dx,{y}+dy,0) if sq then local bodies=sq:getDeadBodys() "
                    "for i=bodies:size()-1,0,-1 do local b=bodies:get(i) if b:isAnimal() then local item=b:getItem() "
                    "if item then p:getInventory():AddItem(item) sq:removeCorpse(b,false) b:invalidateCorpse() n=n+1 end end end end end end "
                    "local items=p:getInventory():getItems() local names={} for i=0,items:size()-1 do names[#names+1]=items:get(i):getFullType() end "
                    "return n..' '..table.concat(names,',')")
    s2, saving = restart(s)
    login = after_login(s2, x, y)
    inventory = ("local p=SplintLabServer.player() local items=p:getInventory():getItems() local names={} "
                 "for i=0,items:size()-1 do names[#names+1]=items:get(i):getFullType() end return table.concat(names,',')")
    out = {"killed": killed, "carried": carried, "save_and_quit": saving, "after_login": login}
    if login["lua_alive"]:
        login["inventory"] = s2.lua(inventory)
        # Carcasses rot with time; let two in-game days pass with them in the pocket.
        off = s2.log_size()
        clock = wait_game_hours(s2, 48)
        later: dict = {"lua_alive": heartbeat_alive(s2)}
        if later["lua_alive"]:
            later.update(clock=clock, inventory=s2.lua(inventory),
                         ghosts_in_update_list=s2.lua(LIST_GHOSTS).split()[-1])
        later["exceptions"] = exceptions(s2.log_since(off))
        out["two_days_later"] = later
    return out


def trailer_relog(s: Session) -> dict:
    """'One person having found a trailer full of chickens and whenever they log on it comes
    back': the player logs out next to a livestock trailer of chicks and hens, logs back in, and
    the chicks reach their growth age."""
    x, y = fresh_spot(s, -240)
    made = s.lua(f"local tsq=getCell():getGridSquare({x}+8,{y}-8,0) local v=addVehicleDebug('Base.Trailer_Livestock',IsoDirections.N,nil,tsq) "
                 "local cd,hd=AnimalDefinitions.getDef('chick'),AnimalDefinitions.getDef('hen') "
                 f"for i=1,6 do local a=addAnimal(getCell(),{x}+8,{y}-8,0,'chick',cd:getRandomBreed()) " + NEAR_GROWTH +
                 "v:addAnimalInTrailer(a) end "
                 f"for i=1,2 do v:addAnimalInTrailer(addAnimal(getCell(),{x}+8,{y}-8,0,'hen',hd:getRandomBreed())) end "
                 "return v:getAnimals():size()")
    before = s.lua(TRAILER_LIST)
    s2, saving = restart(s)
    login = after_login(s2, x, y, 20)
    out = {"made": made, "before": before, "save_and_quit": saving, "after_login": login}
    if login["lua_alive"]:
        out["trailer_after_login"] = s2.lua(TRAILER_LIST)
        off = s2.log_size()
        clock = wait_game_hours(s2, 60)     # 2.5 in-game days; the chicks needed one
        grown: dict = {"lua_alive": heartbeat_alive(s2)}
        if grown["lua_alive"]:
            grown.update(clock=clock, trailer=s2.lua(TRAILER_LIST),
                         census=census(s2, x, y, 20), ghosts_in_update_list=s2.lua(LIST_GHOSTS).split()[-1])
        grown["exceptions"] = exceptions(s2.log_since(off))
        out["grew_after_login"] = grown
    return out


def trailer_unload_water(s: Session) -> dict:
    """Unloading a livestock trailer whose animal entry is over water: removeAnimalFromTrailer
    builds the animal fresh at the trailer's AnimalEntry area (then copies the old one into it),
    and ISRemoveAnimalFromTrailer adds it to the world."""
    s.tp(*LAKE_SHORE)
    # Candidate trailers on the last land columns, facing every way; a new vehicle only has its
    # world position (and so its area centres) after a tick, so they are measured afterwards.
    s.lua("SplintLabServer.cands={} for _,dir in ipairs({IsoDirections.E,IsoDirections.W,IsoDirections.N,IsoDirections.S}) do "
          f"for _,dx in ipairs({{0,1,2,3}}) do local sq=getCell():getGridSquare({LAKE_SHORE[0]}+dx,{LAKE_SHORE[1]}+(#SplintLabServer.cands)*4-24,0) "
          "if sq and not sq:isWaterSquare() then local v=addVehicleDebug('Base.Trailer_Livestock',dir,nil,sq) "
          "if v then table.insert(SplintLabServer.cands,{v=v,dir=tostring(dir)}) end end end end return #SplintLabServer.cands")
    time.sleep(5)
    placed = s.lua(
        "local out,keep={},nil for _,c in ipairs(SplintLabServer.cands) do local v=c.v local e=v:getAreaCenter('AnimalEntry') "
        "local esq=e and getCell():getGridSquare(math.floor(e:getX()),math.floor(e:getY()),0) local water=esq and esq:isWaterSquare() "
        "local vsq=v:getSquare() local onland=vsq and not vsq:isWaterSquare() "
        "out[#out+1]=c.dir..'@'..math.floor(v:getX())..','..math.floor(v:getY())..'->'..(e and (math.floor(e:getX())..','..math.floor(e:getY())) or 'nil')"
        "..(water and ' WATER' or '') if water and onland and not keep then keep=v end end "
        "for _,c in ipairs(SplintLabServer.cands) do if c.v~=keep then c.v:permanentlyRemove() end end SplintLabServer.wt=keep "
        "return (keep and 'entry over water; ' or 'none; ')..table.concat(out,' | ')")
    if "entry over water" not in placed:
        return {"placed": placed, "note": "no trailer placement with its animal entry over water was found"}
    s.lua("local v=SplintLabServer.wt local hd=AnimalDefinitions.getDef('hen') local tsq=v:getSquare() "
          "for i=1,2 do v:addAnimalInTrailer(addAnimal(getCell(),tsq:getX(),tsq:getY(),0,'hen',hd:getRandomBreed())) end return 1")
    before = s.lua("local v=SplintLabServer.wt return v:getAnimals():size()")
    off = s.log_size()
    unloaded = s.lua("local v=SplintLabServer.wt local out={} while v:getAnimals():size()>0 do local a=v:getAnimals():get(0) "
                     "local n=v:removeAnimalFromTrailer(a) if instanceof(n,'IsoAnimal') then n:addToWorld() "
                     "out[#out+1]='id='..n:getAnimalID()..' type='..tostring(n:getAnimalType())..' data='..tostring(n:getData()~=nil)"
                     "..' at='..math.floor(n:getX())..','..math.floor(n:getY())..' water='..tostring(n:getCurrentSquare() and n:getCurrentSquare():isWaterSquare()) "
                     "else out[#out+1]=tostring(n) end end return table.concat(out,'; ')")
    time.sleep(15)
    out = {"placed": placed, "in_trailer": before, "unloaded": unloaded, "lua_alive": heartbeat_alive(s),
           "stats_frozen": stats_frozen(s)}
    if out["lua_alive"]:
        out["census"] = census(s, LAKE_SHORE[0], LAKE_SHORE[1], 14)
        out["ghosts_in_update_list"] = s.lua(LIST_GHOSTS).split()[-1]
    out["exceptions"] = exceptions(s.log_since(off))
    return out


def ovo_farms(s: Session) -> dict:
    """'Loading OvO Farms and leaving it': the player visits Ovo Farms (its ranch animals spawn on
    first load), leaves so the farm unloads while in-game days pass, and comes back, twice. The
    farm's chicks are set one day short of growing, so they cross their growth age while nobody
    is there and the area catches up when it loads again."""
    off = s.log_size()
    s.tp(*OVO_FARMS, settle=45)
    out: dict = {"first_visit": {"census": census(s, *OVO_FARMS, 60), "ghosts_in_update_list": s.lua(LIST_GHOSTS).split()[-1]}}
    out["first_visit"]["chicks_set_near_growth"] = s.lua(
        f"local n=0 for _,a in ipairs(SplintLabServer.animalsOnSquares({OVO_FARMS[0]},{OVO_FARMS[1]},0,60)) do "
        "if a:getData() and a:getAnimalType()=='chick' then " + NEAR_GROWTH + "n=n+1 end end return n")
    # Every animal at the farm by ID, to tell animals that moved from animals that are gone.
    snapshot = (f"local out={{}} for _,a in ipairs(SplintLabServer.animalsOnSquares({OVO_FARMS[0]},{OVO_FARMS[1]},0,60)) do "
                "if a:getData() then out[#out+1]=a:getAnimalID()..':'..tostring(a:getAnimalType()) end end return table.concat(out,' ')")
    first = dict(item.split(":") for item in s.lua(snapshot).split()[2:])
    out["first_visit"]["ids"] = len(first)
    h0 = world_hours(s)
    for i in (1, 2):
        s.tp(OVO_FARMS[0] + 2000, OVO_FARMS[1], settle=30)      # far enough for the farm to unload
        out[f"away_{i}"] = wait_game_hours(s, 60)             # 2.5 in-game days away
        s.tp(*OVO_FARMS, settle=40)
        visit: dict = {"lua_alive": heartbeat_alive(s)}
        if visit["lua_alive"]:
            now = dict(item.split(":") for item in s.lua(snapshot).split()[2:])
            missing = sorted(set(first) - set(now))
            found = s.lua(WHERE_ID + "local out={} for _,id in ipairs({" + ",".join(missing or ["-999"]) + "}) do "
                          "out[#out+1]=id..'='..where(id) end return table.concat(out,'; ')") if missing else ""
            corpses = s.lua(f"local n=0 for dx=-60,60 do for dy=-60,60 do local sq=getCell():getGridSquare({OVO_FARMS[0]}+dx,{OVO_FARMS[1]}+dy,0) "
                            "if sq then local b=sq:getDeadBodys() for i=0,b:size()-1 do if b:get(i):isAnimal() then n=n+1 end end end end end return n")
            visit.update(game_hours_since_first_visit=round(world_hours(s) - h0, 1), census=census(s, *OVO_FARMS, 60),
                         animal_corpses_at_farm=corpses.split()[-1],
                         ghosts_in_update_list=s.lua(LIST_GHOSTS).split()[-1],
                         missing_since_first_visit={k: first[k] for k in missing}, missing_found_elsewhere=found)
        out[f"return_{i}"] = visit
    out["stats_frozen"] = stats_frozen(s)
    out["exceptions"] = exceptions(s.log_since(off))
    return out


def hutch_pocket(s: Session) -> dict:
    """Hutch chicks pass their growth age without growing (the hutch skips stage checks), the
    server restarts, then a player takes one into their pocket the way ISHutchGrabAnimal does.
    A carried animal past its growth age grows at once, at the coordinates it carries, so where
    a hutch animal 'is' decides where its next stage is built."""
    x, y = fresh_spot(s, 960)
    hx, hy = x - 6, y + 6
    made = s.lua(f"local def=HutchDefinitions.hutchs['hutchhen'] local sq=getCell():getGridSquare({hx},{hy},0) "
                 "local h=IsoHutch.new(sq,true,def.baseSprite,def,nil) h:transmitCompleteItemToClients() "
                 # As ISPutAnimalInHutch does: the chick is picked up (out of the world, into the
                 # player's hands), put inside, and the item leaves the inventory.
                 "local cd=AnimalDefinitions.getDef('chick') local p=SplintLabServer.player() local n=0 "
                 f"for i=0,3 do local a=addAnimal(getCell(),{x}+2,{y},0,'chick',cd:getRandomBreed()) a:addToWorld() " + NEAR_GROWTH +
                 "local item=instanceItem('Base.Animal') item:setAnimal(a) a:removeFromWorld() a:removeFromSquare() a:setSquare(nil) "
                 "p:getInventory():AddItem(item) a:getBehavior():setHourBeforeLeavingHutch(1) a:getData():setPreferredHutchPosition(i) "
                 "if h:addAnimalInside(a) then n=n+1 end p:getInventory():Remove(item) end return n")
    inside = (f"local h=IsoHutch.getHutch({hx},{hy},0) if not h then return 'no hutch' end local out={{}} "
              "for i=0,h:getMaxAnimals()-1 do local a=h:getAnimal(i) if a then out[#out+1]=tostring(a:getAnimalType())..'#'"
              "..a:getAnimalID()..'@'..math.floor(a:getX())..','..math.floor(a:getY())..','..math.floor(a:getZ())"
              "..' age='..(a:getData() and a:getData():getAge() or -1) end end return #out..' '..table.concat(out,'; ')")
    clock = wait_game_hours(s, 60)
    before_restart = s.lua(inside)
    s2, saving = restart(s)
    login = after_login(s2, x, y, 15)
    out = {"made": made, "clock_before_restart": clock, "inside_before_restart": before_restart,
           "save_and_quit": saving, "after_login": login}
    if not login["lua_alive"]:
        return out
    out["inside_after_restart"] = s2.lua(inside)
    off = s2.log_size()
    grabbed = s2.lua(f"local h=IsoHutch.getHutch({hx},{hy},0) local p=SplintLabServer.player() "
                     "for i=0,h:getMaxAnimals()-1 do local a=h:getAnimal(i) if a then "
                     "local item=instanceItem('Base.Animal') item:setAnimal(a) h:removeAnimal(a) p:getInventory():AddItem(item) "
                     "h:sync() return a:getAnimalID() end end return -1").split()[-1]
    time.sleep(30)
    grab: dict = {"id": grabbed, "lua_alive": heartbeat_alive(s2), "stats_frozen": stats_frozen(s2),
                  "player_alive": player_alive(s2)}
    if grab["lua_alive"]:
        grab.update(inventory=s2.lua(INVENTORY_ANIMALS), where=s2.lua(WHERE_ID + f"return where({grabbed})"),
                    ghosts_in_update_list=s2.lua(LIST_GHOSTS).split()[-1], census=census(s2, x, y, 15))
    grab["exceptions"] = exceptions(s2.log_since(off))
    out["grabbed_after_restart"] = grab
    return out


# One weapon per weapon category the running game defines (read from its item scripts, so new
# weapons, categories and mods are covered without changing this file), plus one ranged weapon.
WEAPON_PICKS = ("local all=getScriptManager():getAllItems() local seen,picks={},{} for i=0,all:size()-1 do local it=all:get(i) "
                "if not it:getObsolete() then local keys={} if it:isRanged() then keys[#keys+1]='Ranged' end "
                "local cats=it:getWeaponCategories() if cats then local ci=cats:iterator() while ci:hasNext() do keys[#keys+1]=tostring(ci:next()) end end "
                "for _,k in ipairs(keys) do if not seen[k] then seen[k]=it:getFullName() picks[#picks+1]=k..'='..it:getFullName() end end end end "
                "table.sort(picks) SplintLabServer.weapons=picks return table.concat(picks,' ')")


def weapon_kill_butcher(s: Session) -> dict:
    """'I butchered some chickens before I logged': a hen is killed with one weapon from every
    weapon category through the game's own hit code, each carcass is butchered the way
    ISButcherAnimal does, one carcass is carried, and the player logs out and back in while
    in-game days pass."""
    x, y = fresh_spot(s, 1080)
    picks = s.lua(WEAPON_PICKS).split()[2:]
    kills = s.lua(
        "local p=SplintLabServer.player() local hd=AnimalDefinitions.getDef('hen') local out={} SplintLabServer.kills={} "
        "for i,pick in ipairs(SplintLabServer.weapons) do local cat,name=pick:match('([^=]+)=(.+)') "
        "local hen=addAnimal(getCell(),math.floor(p:getX())+2+i,math.floor(p:getY())+2,0,'hen',hd:getRandomBreed()) hen:addToWorld() "
        "local ok,err=pcall(function() local w=instanceItem(name) hen:Hit(w,p,1000,false,1.0) end) "
        "out[#out+1]=cat..(ok and ':hit' or (':error '..tostring(err))) end return table.concat(out,' ')")
    time.sleep(10)
    corpses = (f"local bodies={{}} for dx=-30,30 do for dy=-10,10 do local sq=getCell():getGridSquare({x}+dx,{y}+dy,0) "
               "if sq then local b=sq:getDeadBodys() for i=0,b:size()-1 do if b:get(i):isAnimal() then bodies[#bodies+1]=b:get(i) end end end end end ")
    dead = s.lua(corpses + "return #bodies")
    butchered = s.lua(
        corpses + "local p=SplintLabServer.player() local before=p:getInventory():getItems():size() local ok,bad=0,{} "
        "for i=1,#bodies-1 do local r,err=pcall(ButcheringUtil.butcherAnimalFromGround,bodies[i],p) if r then ok=ok+1 else bad[#bad+1]=tostring(err) end end "
        "if #bodies>0 then local b=bodies[#bodies] local item=b:getItem() if item then p:getInventory():AddItem(item) b:getSquare():removeCorpse(b,false) b:invalidateCorpse() end end "
        "return 'butchered='..ok..' items_gained='..(p:getInventory():getItems():size()-before)..' errors='..table.concat(bad,'|')")
    off = s.log_size()
    s2, saving = restart(s)
    login = after_login(s2, x, y, 30)
    out = {"weapons_by_category": picks, "kills": kills, "animal_corpses": dead, "butchering": butchered,
           "exceptions_before_restart": exceptions(s.log_since(off)), "save_and_quit": saving, "after_login": login}
    if login["lua_alive"]:
        later_off = s2.log_size()
        clock = wait_game_hours(s2, 48)
        later: dict = {"clock": clock, "lua_alive": heartbeat_alive(s2), "player_alive": player_alive(s2)}
        if later["lua_alive"]:
            later["ghosts_in_update_list"] = s2.lua(LIST_GHOSTS).split()[-1]
        later["exceptions"] = exceptions(s2.log_since(later_off))
        out["two_days_later"] = later
    return out


def stored_on_water_growth(s: Session) -> dict:
    """Trailer chicks positioned over water reach their growth age. AnimalData.grow deletes the
    animal and its next stage when checkForWater (which reads the animal's square) says it stands
    on water. Stored animals have no square, so this is a regression check: they must still grow.
    The water position is set directly (a constructed state)."""
    s.tp(*LAKE_SHORE)
    made = s.lua(f"local tsq=getCell():getGridSquare({LAKE_SHORE[0]}-3,{LAKE_SHORE[1]},0) "
                 "local v=addVehicleDebug('Base.Trailer_Livestock',IsoDirections.N,nil,tsq) SplintLabServer.wv=v "
                 "local cd=AnimalDefinitions.getDef('chick') "
                 f"for i=1,3 do local a=addAnimal(getCell(),{LAKE_SHORE[0]}-3,{LAKE_SHORE[1]},0,'chick',cd:getRandomBreed()) v:addAnimalInTrailer(a) end "
                 f"local w=getCell():getGridSquare({LAKE_WATER[0]},{LAKE_WATER[1]},0) "
                 "for i=0,v:getAnimals():size()-1 do local a=v:getAnimals():get(i) a:setX(w:getX()+0.5) a:setY(w:getY()+0.5) a:setCurrent(w) end "
                 "return v:getAnimals():size()..' water='..tostring(w:isWaterSquare())")
    listing = ("local v=SplintLabServer.wv local out={} for i=0,v:getAnimals():size()-1 do local a=v:getAnimals():get(i) "
               "out[#out+1]=tostring(a:getAnimalType())..'#'..a:getAnimalID() end return #out..' '..table.concat(out,',')")
    before = s.lua(listing)
    off = s.log_size()
    s.lua("local v=SplintLabServer.wv for i=0,v:getAnimals():size()-1 do local a=v:getAnimals():get(i) "
          "a:setHoursSurvived(400*24) a:getData():setAge(400) end return 1")
    time.sleep(40)
    out = {"made": made, "before": before, "lua_alive": heartbeat_alive(s)}
    if out["lua_alive"]:
        out.update(after=s.lua(listing), census=census(s, LAKE_SHORE[0], LAKE_SHORE[1], 10),
                   ghosts_in_update_list=s.lua(LIST_GHOSTS).split()[-1])
    out["exceptions"] = exceptions(s.log_since(off))
    return out


def server_zombie_update(s: Session) -> dict:
    """Does the dedicated server run zombie updates at all? MovingObjectUpdateScheduler.startFrame
    skips every IsoZombie when GameServer.server is set (it only counts chasing zombies), so
    zombies never reach the update buckets: connected clients simulate them. Checked here: a
    zombie in the cell's update list gets no OnZombieUpdate events on the server, and clearing its
    legs sprite, which IsoZombie.updateInternal dereferences, throws nothing."""
    made = s.lua("local p=SplintLabServer.player() local zs=addZombiesInOutfit(math.floor(p:getX())+4,math.floor(p:getY()),0,1,nil,50) "
                 "SplintLabServer.z=zs:get(0) SplintLabServer.zc=0 "
                 "SplintLabServer.countZ=function(z) SplintLabServer.zc=SplintLabServer.zc+1 end Events.OnZombieUpdate.Add(SplintLabServer.countZ) "
                 "return tostring(SplintLabServer.z:getClass())")
    in_list = ("local z=SplintLabServer.z local l=getCell():getObjectListForLua() for i=0,l:size()-1 do "
               "if l:get(i)==z then return 'yes' end end return 'no'")
    time.sleep(10)
    out: dict = {"zombie": made, "in_update_list": s.lua(in_list).split()[-1],
                 "on_zombie_update_events_in_10s": s.lua("return SplintLabServer.zc").split()[-1]}
    off = s.log_size()
    s.lua("SplintLabServer.z:setLegsSprite(nil) return 1")
    time.sleep(15)
    out.update(lua_alive=heartbeat_alive(s), still_in_update_list=s.lua(in_list).split()[-1],
               exceptions=exceptions(s.log_since(off)))
    return out


PASTURE = (7333, 8322, 7363, 8347)   # Fallas Lake shore: land up to x=7350, open water from x=7351


def pasture_pond(s: Session) -> dict:
    """Admins' question: can a pasture built over water cause this? A fenced animal zone is made
    the way ISAddDesignationAnimalZoneUI does (DesignationZoneAnimal.new + createSurroundingFence)
    with about a third of it open lake. Farm juveniles with growth ages spread over the next week
    (from the game's stage table) and hens are placed on dry land inside. A server ticker samples
    how many animals stand on water squares while in-game days pass, then the player leaves for
    three days and comes back so the area catches up. Recorded: animals ever on water, ghosts,
    growth, exceptions, whether the world update keeps running."""
    x0, y0, x1, y1 = PASTURE
    s.tp(*LAKE_SHORE)
    made = s.lua(
        f"local z=DesignationZoneAnimal.new('splint pond test',{x0},{y0},0,{x1 + 1},{y1 + 1},true) z:createSurroundingFence() "
        f"local water,land=0,0 for x={x0},{x1} do for y={y0},{y1} do local sq=getCell():getGridSquare(x,y,0) "
        "if sq then if sq:isWaterSquare() then water=water+1 else land=land+1 end end end end "
        "return 'water='..water..' land='..land")
    placed = s.lua(
        "local spots={} "
        f"for x={x0}+1,{x1}-1 do for y={y0}+1,{y1}-1 do local sq=getCell():getGridSquare(x,y,0) "
        "if sq and not sq:isWaterSquare() then spots[#spots+1]={x,y} end end end "
        "local counts={} local function put(t,n,spread) local def=AnimalDefinitions.getDef(t) for i=1,n do "
        "local p=spots[ZombRand(#spots)+1] local a=addAnimal(getCell(),p[1],p[2],0,t,def:getRandomBreed()) "
        "if a and a:getData() then a:addToWorld() if spread then "
        "local st=AnimalDefinitions.stages[a:getAdef():getGroup()] st=st and st.stages[a:getAnimalType()] local g=st and st.ageToGrow "
        "if g then local d=g-1-ZombRand(7) a:getData():setAge(d) a:setHoursSurvived(d*24+ZombRand(24)) end end "
        "counts[t]=(counts[t] or 0)+1 end end end "
        "put('chick',120,true) put('lamb',20,true) put('piglet',20,true) put('hen',30,false) "
        "local out={} for k,v in pairs(counts) do out[#out+1]=k..'='..v end return table.concat(out,' ')")
    # Server-side sampler: animals standing on water inside the pasture, every 10 ticks.
    s.lua(f"SplintLabServer.pond={{max=0,samples=0,wet=0,ids={{}}}} local P=SplintLabServer.pond local tick=0 "
          "SplintLabServer.pondTick=function() tick=tick+1 if tick%10~=0 then return end local w=0 "
          "local l=getCell():getObjectListForLua() for i=0,l:size()-1 do local o=l:get(i) if instanceof(o,'IsoAnimal') then "
          f"local x,y=o:getX(),o:getY() if x>={x0} and x<={x1 + 1} and y>={y0} and y<={y1 + 1} then local sq=o:getCurrentSquare() "
          "if sq and sq:isWaterSquare() then w=w+1 P.ids[o:getAnimalID()]=true end end end end "
          "P.samples=P.samples+1 P.wet=P.wet+w if w>P.max then P.max=w end end "
          "Events.OnTick.Add(SplintLabServer.pondTick) return 1")
    state = ("local n,types,g=0,{},0 local l=getCell():getObjectListForLua() for i=0,l:size()-1 do local o=l:get(i) "
             f"if instanceof(o,'IsoAnimal') then local x,y=o:getX(),o:getY() if x>={x0 - 4} and x<={x1 + 5} and y>={y0 - 4} and y<={y1 + 5} then "
             "n=n+1 local t=tostring(o:getAnimalType()) types[t]=(types[t] or 0)+1 "
             "if o:getData()==nil or o:getAnimalType()==nil then g=g+1 end end end end "
             "local P=SplintLabServer.pond local ever=0 for _ in pairs(P.ids) do ever=ever+1 end local out={} "
             "for k,v in pairs(types) do out[#out+1]=k..'='..v end table.sort(out) "
             "return 'animals='..n..' ghosts='..g..' on_water_max='..P.max..' animals_ever_on_water='..ever"
             "..' samples='..P.samples..' types='..table.concat(out,',')")
    off = s.log_size()
    out: dict = {"pasture": made, "placed": placed, "start": s.lua(state).split(" ", 2)[-1], "checkpoints": []}
    for _ in range(5):
        clock = wait_game_hours(s, 24)
        cp: dict = {"clock": clock, "lua_alive": heartbeat_alive(s, wait=6)}
        if cp["lua_alive"]:
            cp["state"] = s.lua(state).split(" ", 2)[-1]
        out["checkpoints"].append(cp)
        if not cp["lua_alive"]:
            break
    else:
        # Every animal by ID before leaving, to tell animals that come back late from animals that are gone.
        ids = ("local out={} local l=getCell():getObjectListForLua() for i=0,l:size()-1 do local o=l:get(i) "
               f"if instanceof(o,'IsoAnimal') and o:getData() then local x,y=o:getX(),o:getY() if x>={x0 - 4} and x<={x1 + 5} and y>={y0 - 4} and y<={y1 + 5} then "
               "out[#out+1]=o:getAnimalID()..':'..tostring(o:getAnimalType()) end end end return table.concat(out,' ')")
        before = dict(item.split(":", 1) for item in s.lua(ids).split()[2:])
        s.tp(LAKE_SHORE[0] + 2000, LAKE_SHORE[1], settle=30)
        out["away"] = wait_game_hours(s, 72)
        s.tp(*LAKE_SHORE, settle=40)
        back: dict = {"lua_alive": heartbeat_alive(s), "ids_before_leaving": len(before)}
        if back["lua_alive"]:
            waited = 0
            for mark in (20, 80, 200):
                time.sleep(mark - waited)
                waited = mark
                now = dict(item.split(":", 1) for item in s.lua(ids).split()[2:])
                missing = [k for k in before if k not in now]
                kinds: dict = {}
                for k in missing:
                    kinds[before[k]] = kinds.get(before[k], 0) + 1
                corpses = s.lua(f"local t={{}} for x={x0 - 4},{x1 + 5} do for y={y0 - 4},{y1 + 5} do local sq=getCell():getGridSquare(x,y,0) "
                                "if sq then local b=sq:getDeadBodys() for i=0,b:size()-1 do local d=b:get(i) if d:isAnimal() then "
                                "local k=tostring(d:getAnimalType()) t[k]=(t[k] or 0)+1 end end end end end "
                                "local out={} for k,v in pairs(t) do out[#out+1]=k..'='..v end return '['..table.concat(out,',')..']'")
                back[f"after_{mark}s"] = {"state": s.lua(state).split(" ", 2)[-1], "missing_ids": len(missing),
                                          "missing_by_type_when_left": kinds, "animal_corpses": corpses.split()[-1]}
            if missing:
                found = s.lua(WHERE_ID + "local n=0 for _,id in ipairs({" + ",".join(missing[:60]) + "}) do "
                              "if not where(id):find('^0 ') then n=n+1 end end return n").split()[-1]
                back["missing_found_anywhere_in_update_list"] = f"{found} of {min(len(missing), 60)} checked"
        out["after_leaving_and_returning"] = back
    out["stats_frozen_note"] = "player is invincible in the chamber, so stats are not a health signal here"
    out["exceptions"] = exceptions(s.log_since(off))
    if json.loads((s.evidence / "session.json").read_text()).get("agent"):
        status = console_reply(s, "splint status", wait=8)
        out["splint_status"] = " ".join(re.findall(r"(?:waterRelocations|ghostsRemoved|ghostsPrevented|failuresContained)=\d+", status))
    return out


def aging_rates(s: Session) -> dict:
    """How fast animals age in each place they can be (on the map, carried, in a livestock
    trailer, in a hutch), measured as days of age gained per in-game day, at a slow clock and in
    the time chamber. The slow clock is the reference: an update the game throttles in real time
    falls behind in the chamber, so a difference between the two is a chamber artefact, while a
    rate other than the sandbox AnimalAgeModifier at the slow clock is the game's behaviour."""
    x, y = fresh_spot(s, 1200)
    hx, hy = x - 6, y + 6
    made = s.lua(
        "local p=SplintLabServer.player() p:setGodMod(true) p:setInvincible(true) local cd=AnimalDefinitions.getDef('chick') "
        "SplintLabServer.places={world={},pocket={},trailer={},hutch={}} local P=SplintLabServer.places "
        "local function chick(dx,dy) local a=addAnimal(getCell(),math.floor(p:getX())+dx,math.floor(p:getY())+dy,0,'chick',cd:getRandomBreed()) "
        "a:addToWorld() a:getData():setAge(10) a:setHoursSurvived(240) return a end "
        "for i=1,3 do table.insert(P.world, chick(3,i)) end "
        "for i=1,3 do local a=chick(2,-i) local item=instanceItem('Base.Animal') item:setAnimal(a) a:removeFromWorld() a:removeFromSquare() "
        "a:setSquare(nil) p:getInventory():AddItem(item) table.insert(P.pocket,a) end "
        f"local v=addVehicleDebug('Base.Trailer_Livestock',IsoDirections.N,nil,getCell():getGridSquare({x}+8,{y}-8,0)) "
        f"for i=1,3 do local a=addAnimal(getCell(),{x}+8,{y}-8,0,'chick',cd:getRandomBreed()) a:getData():setAge(10) a:setHoursSurvived(240) "
        "v:addAnimalInTrailer(a) table.insert(P.trailer,a) end "
        f"local def=HutchDefinitions.hutchs['hutchhen'] local h=IsoHutch.new(getCell():getGridSquare({hx},{hy},0),true,def.baseSprite,def,nil) "
        "h:transmitCompleteItemToClients() for i=1,3 do local a=chick(1,1) local item=instanceItem('Base.Animal') item:setAnimal(a) "
        "a:removeFromWorld() a:removeFromSquare() a:setSquare(nil) a:getBehavior():setHourBeforeLeavingHutch(1000) "
        "a:getData():setPreferredHutchPosition(i-1) h:addAnimalInside(a) table.insert(P.hutch,a) end "
        "return 'world='..#P.world..' pocket='..#P.pocket..' trailer='..#P.trailer..' hutch='..#P.hutch")
    ages = ("local out={} for _,k in ipairs({'world','pocket','trailer','hutch'}) do local t={} "
            "for _,a in ipairs(SplintLabServer.places[k]) do t[#t+1]=(a:getData() and a:getData():getAge() or -1) end "
            "out[#out+1]=k..'='..table.concat(t,',') end return table.concat(out,' ')")

    def parse(text: str) -> dict:
        out = {}
        for part in text.split()[2:]:
            k, v = part.split("=")
            vals = [int(n) for n in v.split(",") if n and n != "-1"]
            out[k] = sum(vals) / len(vals) if vals else None
        return out

    def measure(label: str, mpd: float, speed: float, hours: float) -> dict:
        a0 = parse(s.lua(ages))
        h0 = world_hours(s)
        set_time(s, mpd, speed)
        t0 = time.time()
        while world_hours(s) - h0 < hours and time.time() - t0 < 900:
            time.sleep(2)
        set_time(s, 60, 1)
        days = (world_hours(s) - h0) / 24
        a1 = parse(s.lua(ages))
        rates = {k: (round((a1[k] - a0[k]) / days, 2) if a0.get(k) is not None and a1.get(k) is not None else None) for k in a0}
        return {"clock": label, "game_days": round(days, 2), "days_of_age_per_game_day": rates}

    return {"made": made, "age_modifier": s.lua("return SandboxVars.AnimalAgeModifier or 'unset'").split()[-1],
            "slow": measure("1 minute per day", 1, 1, 48), "chamber": measure("1 minute per day x settimespeed 20", 1, 20, 72),
            "exceptions": exceptions(s.log_since(s.ready_offset))}


CHAMBER_SETTINGS = [(60, 1), (1, 1), (1, 5), (1, 20), (1, 40), (0.25, 40), (0.25, 100)]


def time_chamber(s: Session) -> dict:
    """How fast can the lab run the game's clock and still trust it? A farm of 200 chicks (a few
    in-game days from growing) and 50 hens runs under each (minutes per day, settimespeed) pair
    for 30 real seconds. Each row: in-game hours per real second, ticks per second, server CPU per
    tick, whether the world update stays alive, exceptions, chicks grown so far and animals in the
    update list (growth keeps that number: a drop means animals were lost)."""
    x, y = fresh_spot(s, 300)
    s.lua("local p=SplintLabServer.player() p:setGodMod(true) p:setInvincible(true) return 1")
    s.server(f"spawn chick - 200 0 0 12 {grows_at(s, 'chicken', 'chick') - 6}")
    s.server("spawn hen - 50 0 0 12 200")
    count_types = (f"local c,a=0,0 for _,an in ipairs(SplintLabServer.animalsOnSquares({x},{y},0,16)) do "
                   "local t=an:getAnimalType() if t=='chick' then c=c+1 elseif t=='hen' or t=='cockerel' then a=a+1 end end "
                   "return c..' '..a")
    rows = []
    for mpd, speed in CHAMBER_SETTINGS:
        set_time(s, mpd, speed)
        off = s.log_size()
        h0 = world_hours(s)
        stats = tick_stats(s, window=30)
        alive = heartbeat_alive(s, wait=6)
        row = {"minutes_per_day": mpd, "timespeed": speed, "lua_alive": alive, **stats,
               "exceptions": exceptions(s.log_since(off))}
        if alive:
            h1 = world_hours(s)
            chicks, adults = s.lua(count_types).split()[-2:]
            corpses = s.lua(f"local n=0 for dx=-40,40 do for dy=-40,40 do local sq=getCell():getGridSquare({x}+dx,{y}+dy,0) "
                            "if sq then local b=sq:getDeadBodys() for i=0,b:size()-1 do if b:get(i):isAnimal() then n=n+1 end end end end end return n")
            row.update(game_hours_per_real_second=round((h1 - h0) / 36, 3), chicks=int(chicks), adults=int(adults),
                       animals_in_update_list=int(s.lua(TOTAL_ANIMALS).split()[-1]), animal_corpses=int(corpses.split()[-1]))
        rows.append(row)
        if not alive:
            break
    set_time(s, 60, 1)
    return {"rows": rows}


OPEN_QUESTIONS = {f.__name__.replace("_", "-"): f for f in
                  (ghost_save_restart, ghost_save_splint_resume, pocket_far_growth, pocket_relog, carcass_relog,
                   trailer_relog, trailer_unload_water, ovo_farms, hutch_pocket, weapon_kill_butcher)}


SCENARIOS = {f.__name__.replace("_", "-"): f for f in
             (mass_growth, duplicate_ids, invalid_type, water_seed, grow_near_ghost, grow_near_invalid_ghost,
              malformed_breed, legacy_dup_reload, unload_with_ghost)}
# Needs the agent; run with --only admin-commands.
EXTRA = {"admin-commands": admin_commands, "stress-2000": stress_2000, "stress-5000": stress_5000}
# Storage-place growth investigations.
EXTRA.update({f.__name__.replace('_', '-'): f for f in (inventory_growth, trailer_growth, hutch_growth)})
EXTRA.update(OPEN_QUESTIONS)
EXTRA["time-chamber"] = time_chamber
EXTRA["aging-rates"] = aging_rates
EXTRA["stored-on-water-growth"] = stored_on_water_growth
EXTRA["server-zombie-update"] = server_zombie_update
EXTRA["pasture-pond"] = pasture_pond


def start(evidence: Path, agent: str | None, resume: Path | None = None) -> Session:
    # The stock dedicated server runs with -Xmx8g (ProjectZomboid64.json); 5000 animated animals
    # need it. SPLINT_SERVER_HEAP overrides.
    cmd = [sys.executable, str(LAB / "splintlab.py"), "start", str(evidence),
           "--server-heap", os.environ.get("SPLINT_SERVER_HEAP", "8g")]
    if agent:
        cmd += ["--agent", agent]
    if resume:
        cmd += ["--resume", str(resume)]
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
    s.ready_offset = s.log_size()
    if agent:
        # A patched run is only evidence if the patch is live in this JVM.
        status = console_reply(s, "splint status", wait=8)
        if "patches applied" not in status or "MISSING" in status:
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
        for child in s.children:
            stop(child)
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
