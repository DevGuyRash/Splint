#!/usr/bin/env python3
"""Build Splint: compiles the Java agent against the installed game and packages dist/.

    python3 build.py [--game-jar PATH] [--verify] [--test] [--strict]

--verify applies every patch to the game jar offline and runs the JDK bytecode verifier, so
a game update that renames or changes a patched method shows up before a server starts, then
boots the game's own JRE with the agent. --strict makes a missing game JRE an error instead
of skipping that last check (releases use it).

Every file in dist/ is a release artifact (see tools/release.py); dist/ is rebuilt from
scratch on each run so nothing stale ships.

Needs a JDK (javac/jar) that can target Java 25, the game's bundled runtime. The game jar
is only a compile-time classpath; it is never copied into the output.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CANDIDATES = [
    ROOT / ".lab/server/java/projectzomboid.jar",
    Path.home() / ".steam/steam/steamapps/common/Project Zomboid Dedicated Server/java/projectzomboid.jar",
    Path.home() / ".steam/steam/steamapps/common/ProjectZomboid/projectzomboid/projectzomboid.jar",
]
VERSION_FILE = ROOT / "agent/src/splint/core/Splint.java"


def version() -> str:
    for line in VERSION_FILE.read_text().splitlines():
        if "VERSION =" in line:
            return line.split('"')[1]
    raise SystemExit("VERSION not found")


def source_date() -> str:
    """Timestamp for every jar entry: the last commit that touched the agent, so the same source
    always builds the same bytes (SOURCE_DATE_EPOCH overrides; outside Git, a fixed date)."""
    if os.environ.get("SOURCE_DATE_EPOCH"):
        when = int(os.environ["SOURCE_DATE_EPOCH"])
    else:
        try:
            when = int(subprocess.run(["git", "log", "-1", "--format=%ct", "--", "agent", "build.py"], cwd=ROOT,
                                      check=True, capture_output=True, text=True).stdout.strip() or 315532800)
        except (OSError, subprocess.CalledProcessError):
            when = 315532800                              # 1980-01-01, the earliest date a jar can hold
    return datetime.fromtimestamp(max(when, 315532800), timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--game-jar", default=os.environ.get("SPLINT_GAME_JAR"))
    p.add_argument("--verify", action="store_true")
    p.add_argument("--test", action="store_true", help="run the unit tests in agent/test")
    p.add_argument("--strict", action="store_true", help="fail instead of skipping the in-game check")
    a = p.parse_args()
    jar = Path(a.game_jar) if a.game_jar else next((c for c in CANDIDATES if c.exists()), None)
    if not jar or not jar.exists():
        sys.exit("projectzomboid.jar not found; pass --game-jar")
    build = ROOT / "build"
    classes = build / "classes"
    shutil.rmtree(build, ignore_errors=True)
    classes.mkdir(parents=True)
    sources = sorted(str(s) for s in (ROOT / "agent/src").rglob("*.java"))
    subprocess.run(["javac", "--release", "25", "-Xlint:all,-serial,-processing", "-Werror", "-encoding", "UTF-8",
                    "-cp", str(jar), "-d", str(classes), *sources], check=True)
    # Resources (the module service list) go into the jar beside the classes.
    shutil.copytree(ROOT / "agent/resources", classes, dirs_exist_ok=True)
    manifest = build / "MANIFEST.MF"
    manifest.write_text(f"Premain-Class: splint.agent.SplintAgent\nImplementation-Title: Splint\n"
                        f"Implementation-Version: {version()}\n")
    dist = ROOT / "dist"
    shutil.rmtree(dist, ignore_errors=True)
    dist.mkdir()
    out = dist / "splint.jar"
    subprocess.run(["jar", "--create", "--date", source_date(), "--file", str(out), "--manifest", str(manifest),
                    "-C", str(classes), "."], check=True)
    print(f"built {out} ({out.stat().st_size} bytes) against {jar}")
    if a.test:
        test_classes = build / "test-classes"
        test_classes.mkdir()
        tests = sorted((ROOT / "agent/test").rglob("*Test.java"))
        cp = f"{classes}{os.pathsep}{jar}"
        subprocess.run(["javac", "--release", "25", "-encoding", "UTF-8", "-cp", cp, "-d", str(test_classes),
                        *map(str, tests)], check=True)
        for test in tests:
            name = ".".join(test.relative_to(ROOT / "agent/test").with_suffix("").parts)
            subprocess.run(["java", "-cp", f"{test_classes}{os.pathsep}{cp}", name], check=True)
    if a.verify:
        subprocess.run(["java", "-cp", f"{out}{os.pathsep}{jar}", str(ROOT / "tools/VerifyPatches.java"), str(jar)],
                       check=True)
        smoke(out, jar, a.strict)


def smoke(agent: Path, jar: Path, strict: bool) -> None:
    """Loads every patch target in the game's own JRE with the agent attached."""
    jre = jar.parent.parent / "jre64/bin/java"          # <server>/java/projectzomboid.jar
    if not jre.exists():
        jre = jar.parent / "jre64/bin/java"              # <client>/projectzomboid.jar
    if not jre.exists():
        if strict:
            sys.exit(f"agent smoke test cannot run: no game JRE next to {jar}")
        print("agent smoke test skipped: no game JRE next to", jar)
        return
    tools = ROOT / "build/tools"
    tools.mkdir(parents=True, exist_ok=True)
    subprocess.run(["javac", "--release", "25", "-d", str(tools), str(ROOT / "tools/AgentSmoke.java")], check=True)
    targets = subprocess.run(["java", "-cp", f"{agent}{os.pathsep}{jar}", str(ROOT / "tools/ListTargets.java")],
                             check=True, capture_output=True, text=True).stdout.split()
    subprocess.run([str(jre), f"-javaagent:{agent}", "-cp", f"{tools}{os.pathsep}{jar}", "AgentSmoke", *targets], check=True)
    # Behaviour checks: every tools/*Check.java runs in the game's JRE with the agent attached and
    # fails the build by exiting non-zero. A module adds one by adding a file.
    for check in sorted((ROOT / "tools").glob("*Check.java")):
        subprocess.run(["javac", "--release", "25", "-d", str(tools), str(check)], check=True)
        subprocess.run([str(jre), f"-javaagent:{agent}", "--enable-native-access=ALL-UNNAMED", "-cp",
                        f"{tools}{os.pathsep}{jar}", check.stem], check=True)


if __name__ == "__main__":
    main()
