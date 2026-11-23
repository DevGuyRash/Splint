#!/usr/bin/env python3
"""Markdown comparison of scenario results: vanilla runs against Splint runs.

    report.py --vanilla RESULTS.json [...] --splint RESULTS.json [...]

Later files win when a scenario appears in several runs of the same kind.
"""

from __future__ import annotations

import argparse
import json
import re


def load(paths: list[str]) -> dict:
    out: dict = {}
    for p in paths:
        out.update(json.load(open(p)))
    return out


def short(msg: str) -> str:
    m = re.search(r'Cannot (?:read field|invoke) "([^"]+)"', msg)
    return m.group(1).split(".")[-1].rstrip("()") if m else msg[:40]


def summary(v: dict | None) -> str:
    if v is None:
        return "not run"
    if "harness_error" in v:
        return "harness error"
    parts = []
    ex = v.get("exceptions") or {}
    real = {k: n for k, n in ex.items() if "attempted index" not in k}
    parts.append("no exceptions" if not real else ", ".join(f"{n}× `{short(k)}`" for k, n in real.items()))
    alive = v.get("lua_alive", v.get("back_lua_alive"))
    if alive is not None:
        parts.append("world update running" if alive else "**world update stopped** (Lua ticks dead)")
    if v.get("stats_frozen"):
        parts.append("player stats frozen")
    if "lost" in v:
        parts.append(f"{v['lost']} animals lost")
    if "tps_with_animals" in v:
        parts.append(f"ticks/s {v.get('tps_empty')} empty, {v['tps_with_animals']} loaded, {v.get('tps_after_growth')} after growth")
    if v.get("player_alive") is False:
        parts.append("**player died** (result invalid)")
    if "all_checks_pass" in v:
        parts.append("all command checks pass" if v["all_checks_pass"] else "**command checks failed**")
    groups = [re.search(r"dupIdGroups=(\d+)", v[k]) for k in ("before", "after") if isinstance(v.get(k), str)]
    groups = [g.group(1) for g in groups if g]
    if len(groups) == 2:
        parts.append(f"duplicate-ID groups {groups[0]} → {groups[1]}")
    elif groups:
        parts.append(f"{groups[0]} duplicate-ID groups")
    return "; ".join(parts)


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--vanilla", nargs="+", required=True)
    p.add_argument("--splint", nargs="+", required=True)
    a = p.parse_args()
    van, spl = load(a.vanilla), load(a.splint)
    names = list(dict.fromkeys([*van, *spl]))
    print("| Scenario | Vanilla 42.22.2 | With Splint |")
    print("|---|---|---|")
    for n in names:
        print(f"| `{n}` | {summary(van.get(n))} | {summary(spl.get(n))} |")


if __name__ == "__main__":
    main()
