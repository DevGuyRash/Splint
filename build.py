#!/usr/bin/env python3
"""Build Splint: compiles the Java agent against the installed game and packages dist/.

    python3 build.py [--game-jar PATH] [--verify] [--test]

--verify applies every patch to the game jar offline and runs the JDK bytecode verifier, so
a game update that renames or changes a patched method shows up before a server starts.

Needs a JDK (javac/jar) that can target Java 25, the game's bundled runtime. The game jar
is only a compile-time classpath; it is never copied into the output.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
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


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--game-jar", default=os.environ.get("SPLINT_GAME_JAR"))
    p.add_argument("--verify", action="store_true")
    p.add_argument("--test", action="store_true", help="run the unit tests in agent/test")
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
    dist.mkdir(exist_ok=True)
    out = dist / "splint.jar"
    subprocess.run(["jar", "--create", "--file", str(out), "--manifest", str(manifest), "-C", str(classes), "."],
                   check=True)
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


if __name__ == "__main__":
    main()
