#!/usr/bin/env python3
"""Splint lab: a disposable loopback multiplayer session for reproducing server bugs.

A session is a dedicated server (the steamcmd install of app 380870), a private Xvfb display
and a headless game client from the local Project Zomboid install, each with its own cache
under an evidence directory. Fixture mods in the caches read sequence-numbered command files
and print [SPLAB] / [SPLAB-S] lines that this script waits for. The connected client is what
keeps chunks loaded around a real player, exactly as on a live server.

    splintlab.py start  EVIDENCE [--agent JAR] [--sandbox Key=Value ...]   (stays in foreground)
    splintlab.py server EVIDENCE <fixture command...>
    splintlab.py client EVIDENCE <fixture command...>
    splintlab.py console EVIDENCE <server console line...>
    splintlab.py wait   EVIDENCE (server|client) <text> [--timeout S]
    splintlab.py stop   EVIDENCE

The lab never accepts the game's terms of service itself: it copies the acceptance already
recorded in ~/Zomboid/options.ini by the user's own game.
"""

from __future__ import annotations

import argparse
import json
import os
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FIXTURES = REPO / "lab" / "fixtures"
DEFAULT_SERVER = REPO / ".lab" / "server"
DEFAULT_CLIENT = Path.home() / ".steam/steam/steamapps/common/ProjectZomboid/projectzomboid"
SERVER_NAME = "splintlab"

# No zombies (the lab measures animals, not survival); everything else stays at defaults
# unless --sandbox overrides it.
SANDBOX_BASE = {
    "Zombies": "4",
    "StarterKit": "false",
}
ZOMBIE_CONFIG = "ZombieConfig = { PopulationMultiplier = 0.0, PopulationStartMultiplier = 0.0, " \
                "PopulationPeakMultiplier = 0.0, RespawnHours = 0.0, RedistributeHours = 0.0 }"

SCRUB_ENV = ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "CLASSPATH", "LD_PRELOAD"]


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def accepted_terms() -> str:
    options = Path.home() / "Zomboid/options.ini"
    for line in options.read_text().splitlines():
        if line.startswith("termsOfServiceVersion="):
            return line + "\n"
    sys.exit("accept the game's terms of service in your own game before using the lab")


def mod_id(fixture: Path) -> str:
    for line in (fixture / "42/mod.info").read_text().splitlines():
        if line.startswith("id="):
            return line[3:].strip()
    raise SystemExit(f"no id= in {fixture}/42/mod.info")


def java_env(lib_dirs: list[Path]) -> dict[str, str]:
    env = {k: v for k, v in os.environ.items() if k not in SCRUB_ENV}
    env["LD_LIBRARY_PATH"] = ":".join(str(d) for d in lib_dirs)
    return env


def lua_value(text: str) -> str:
    if text in ("true", "false") or text.replace(".", "", 1).lstrip("-").isdigit():
        return text
    return json.dumps(text)


_DISPLAY_PIPES: list[int] = []


def start_display(log: Path) -> tuple[subprocess.Popen, str]:
    """Starts a private Xvfb and returns it with its display name.

    Xvfb reports the display it picked on the -displayfd pipe; the read end stays open for
    the session, since Xvfb exits if it cannot write there. Two labs starting at the same
    moment can race for a display number, so a display that dies right away is retried."""
    for attempt in range(5):
        read_fd, write_fd = os.pipe()
        proc = subprocess.Popen(
            ["Xvfb", "-displayfd", str(write_fd), "-screen", "0", "1280x900x24", "-nolisten", "tcp"],
            pass_fds=(write_fd,), stdout=log.open("a"), stderr=subprocess.STDOUT,
        )
        os.close(write_fd)
        number = b""
        while not number.endswith(b"\n"):
            chunk = os.read(read_fd, 32)
            if not chunk:
                break
            number += chunk
        _DISPLAY_PIPES.append(read_fd)
        time.sleep(1.5)
        if number.strip() and proc.poll() is None:
            return proc, f":{number.decode().strip()}"
        proc.kill()
        time.sleep(1 + attempt)
    raise SystemExit("Xvfb did not start")


def wait_log(log: Path, needle: str, timeout: float, offset: int = 0, alive=None) -> str:
    deadline = time.time() + timeout
    while True:
        if log.exists():
            with log.open("r", errors="replace") as f:
                f.seek(offset)
                for line in f:
                    if needle in line:
                        return line.rstrip("\n")
        if alive and not alive():
            raise SystemExit(f"process exited while waiting for {needle!r}")
        if time.time() > deadline:
            raise SystemExit(f"timed out waiting for {needle!r} in {log}")
        time.sleep(0.2)


def install_mods(mods_dir: Path) -> list[str]:
    mods_dir.mkdir(parents=True, exist_ok=True)
    ids = []
    for fixture in sorted(FIXTURES.iterdir()):
        shutil.copytree(fixture, mods_dir / fixture.name, dirs_exist_ok=True)
        ids.append(mod_id(fixture))
    for extra in os.environ.get("SPLINT_LAB_MODS", "").split(os.pathsep):
        if extra:
            src = Path(extra)
            shutil.copytree(src, mods_dir / src.name, dirs_exist_ok=True)
            ids.append(mod_id(src))
    return ids


def cmd_start(args: argparse.Namespace) -> None:
    evidence = Path(args.evidence).resolve()
    if evidence.exists() and any(evidence.iterdir()):
        sys.exit(f"evidence directory already exists: {evidence}")
    evidence.mkdir(parents=True, exist_ok=True)
    server_dir = Path(args.server).resolve()
    client_dir = Path(args.client).resolve()
    procs: list[subprocess.Popen] = []

    def shutdown(*_):
        for p in reversed(procs):
            if p.poll() is None:
                p.terminate()
        for p in reversed(procs):
            try:
                p.wait(timeout=20)
            except subprocess.TimeoutExpired:
                p.kill()
        sys.exit(0)

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)

    # Server cache and configuration.
    server_cache = evidence / "server-cache"
    ids = install_mods(server_cache / "mods")
    port, udp = free_port(), free_port()
    while udp == port:
        udp = free_port()
    admin, password = "splintadmin", secrets.token_hex(12)
    (server_cache / "Server").mkdir(parents=True, exist_ok=True)
    (server_cache / "Lua").mkdir(parents=True, exist_ok=True)
    (server_cache / "Server" / f"{SERVER_NAME}.ini").write_text(
        f"DefaultPort={port}\nUDPPort={udp}\nMods={';'.join(ids)}\nWorkshopItems=\nDoLuaChecksum=false\n"
        "Public=false\nUPnP=false\nOpen=true\nAutoCreateUserInWhiteList=true\nMaxPlayers=8\n"
        "PVP=false\nPauseEmpty=false\nMap=Muldraugh, KY\nPassword=\nRCONPassword=\n"
        "SleepAllowed=false\nSpawnPoint=0,0,0\nBackupsOnStart=false\nBackupsOnVersionChange=false\n"
    )
    sandbox = dict(SANDBOX_BASE)
    for kv in args.sandbox or []:
        key, _, value = kv.partition("=")
        sandbox[key] = value
    body = ",\n  ".join(f"{k} = {lua_value(v)}" for k, v in sandbox.items())
    (server_cache / "Server" / f"{SERVER_NAME}_SandboxVars.lua").write_text(
        f"SandboxVars = {{ VERSION = 6,\n  {body},\n  {ZOMBIE_CONFIG} }}\n"
    )

    vm = ["-Xms512m", f"-Xmx{args.server_heap}", "-XX:+UseZGC", "--enable-native-access=ALL-UNNAMED",
          "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "-Djava.awt.headless=true",
          "-Dzomboid.steam=0", "-Djava.library.path=linux64/:./", "-XX:-OmitStackTraceInFastThrow"]
    if args.agent:
        # Run from a private copy: rebuilding dist/ must not change a jar a live JVM reads lazily.
        agent_copy = evidence / "splint-agent.jar"
        shutil.copy2(Path(args.agent).resolve(), agent_copy)
        vm.insert(0, f"-javaagent:{agent_copy}")
    server_log = evidence / "server.log"
    server = subprocess.Popen(
        [str(server_dir / "jre64/bin/java"), *vm, "-cp", "java/.:java/projectzomboid.jar",
         "zombie.network.GameServer", f"-cachedir={server_cache}", "-nosteam", "-servername", SERVER_NAME,
         "-ip", "127.0.0.1", "-port", str(port), "-udpport", str(udp),
         "-adminusername", admin, "-adminpassword", password],
        cwd=server_dir, env=java_env([server_dir / "linux64", server_dir, server_dir / "jre64/lib"]),
        stdin=subprocess.PIPE, stdout=server_log.open("w"), stderr=subprocess.STDOUT, text=True,
    )
    procs.append(server)
    state = {"owner_pid": os.getpid(), "server_pid": server.pid, "port": port, "admin": admin,
             "agent": args.agent, "sandbox": sandbox}
    (evidence / "session.json").write_text(json.dumps(state, indent=2) + "\n")
    console_txt = server_cache / "server-console.txt"
    wait_log(console_txt, "SERVER STARTED", 900, alive=lambda: server.poll() is None)
    print("server started", flush=True)

    if not args.no_client:
        display, display_name = start_display(evidence / "display.log")
        procs.insert(0, display)
        client_cache = evidence / "client-cache"
        cids = install_mods(client_cache / "mods")
        (client_cache / "Lua").mkdir(parents=True, exist_ok=True)
        (client_cache / "Lua" / "splintlab-connect.txt").write_text(f"127.0.0.1\n{port}\n{admin}\n{password}\n")
        (client_cache / "options.ini").write_text(
            "version=8\nwidth=1280\nheight=900\nfullScreen=false\nborderless=false\ntutorialDone=true\n"
            "doneNewSaveFolder=true\nshowFirstTimeSneakTutorial=false\nshowFirstTimeSearchTutorial=false\n"
            "showSurvivalGuide=false\n" + accepted_terms()
        )
        (client_cache / "mods" / "reset-mods-42_00.txt").write_text("disposable lab cache\n")
        menu = "".join(f"    mod = {i},\n" for i in cids)
        (client_cache / "mods" / "default.txt").write_text(f"VERSION = 1,\nmods\n{{\n{menu}}}\nmaps\n{{\n}}\n")
        cenv = java_env([client_dir, client_dir / "natives", client_dir / "jre64/lib"])
        cenv["DISPLAY"] = display_name
        client_cmd = [str(client_dir / "jre64/bin/java"), "-Xms1024m", f"-Xmx{args.client_heap}", "-XX:+UseZGC",
                      "--enable-native-access=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                      "-Djava.library.path=.:natives", "-Dzomboid.steam=0", "-Djava.awt.headless=true",
                      "-cp", ".:projectzomboid.jar", "zombie.gameStates.MainScreenState",
                      f"-cachedir={client_cache}", "-nosteam", "-nosound", "-novoip", "-modfolders", "mods"]
        client_console = client_cache / "console.txt"
        # A join can fail on a loaded machine ("Timed out waiting for the server to send player
        # data"); the game then sits at an error. Relaunch the client, up to three attempts.
        for attempt in range(1, 4):
            client = subprocess.Popen(client_cmd, cwd=client_dir, env=cenv,
                                      stdout=(evidence / f"client-{attempt}.log").open("w"), stderr=subprocess.STDOUT)
            procs.insert(0, client)
            state.update(client_pid=client.pid, display_pid=display.pid, display=display_name, client_attempt=attempt)
            (evidence / "session.json").write_text(json.dumps(state, indent=2) + "\n")
            deadline = time.time() + 1200
            outcome = None
            while outcome is None:
                text = client_console.read_text(errors="replace") if client_console.exists() else ""
                if "[SPLAB] READY" in text:
                    outcome = "ready"
                elif "Timed out waiting for the server" in text or client.poll() is not None or time.time() > deadline:
                    outcome = "failed"
                elif server.poll() is not None:
                    shutdown()
                else:
                    # The loading screen waits for a click once the world is loaded.
                    if "exit zombie.gameStates.LoadingQueueState" in text:
                        env = dict(os.environ, DISPLAY=display_name)
                        subprocess.run(["xdotool", "mousemove", "640", "450", "mousedown", "1"], env=env)
                        time.sleep(1.5)
                        subprocess.run(["xdotool", "mouseup", "1"], env=env)
                    time.sleep(3)
            if outcome == "ready":
                break
            print(f"client attempt {attempt} failed; relaunching", flush=True)
            client.terminate()
            try:
                client.wait(timeout=30)
            except subprocess.TimeoutExpired:
                client.kill()
            procs.remove(client)
            if client_console.exists():
                client_console.rename(client_cache / f"console-attempt{attempt}.txt")
        else:
            print("client never reached the game", flush=True)
            shutdown()
        print("client ready", flush=True)

    # Forward console lines appended to console-in.txt into the server's stdin.
    console_in = evidence / "console-in.txt"
    console_in.touch()
    sent = 0
    while server.poll() is None:
        lines = console_in.read_text().splitlines()
        for line in lines[sent:]:
            assert server.stdin is not None
            server.stdin.write(line + "\n")
            server.stdin.flush()
        sent = len(lines)
        if procs[0] is not server and procs[0].poll() is not None and not args.no_client:
            print("client exited", flush=True)
        time.sleep(0.25)
    shutdown()


def send(evidence: Path, side: str, line: str, timeout: float) -> str:
    cache = evidence / ("server-cache" if side == "server" else "client-cache")
    cmd_file = cache / "Lua" / f"splintlab-{side}-cmd.txt"
    log = cache / ("server-console.txt" if side == "server" else "console.txt")
    seq = int(time.time() * 1000)
    offset = log.stat().st_size if log.exists() else 0
    tmp = cmd_file.with_suffix(".tmp")
    tmp.write_text(f"{seq} {line}\n")
    tmp.rename(cmd_file)
    marker = f"[SPLAB{'-S' if side == 'server' else ''}] ACK {seq} "
    return wait_log(log, marker, timeout, offset)


def cmd_send(args: argparse.Namespace) -> None:
    print(send(Path(args.evidence).resolve(), args.side, " ".join(args.line), args.timeout))


def cmd_console(args: argparse.Namespace) -> None:
    with (Path(args.evidence).resolve() / "console-in.txt").open("a") as f:
        f.write(" ".join(args.line) + "\n")


def cmd_wait(args: argparse.Namespace) -> None:
    evidence = Path(args.evidence).resolve()
    log = evidence / ("server-cache/server-console.txt" if args.side == "server" else "client-cache/console.txt")
    print(wait_log(log, args.text, args.timeout))


def cmd_stop(args: argparse.Namespace) -> None:
    state = json.loads((Path(args.evidence).resolve() / "session.json").read_text())
    pids = [state.get(k) for k in ("owner_pid", "client_pid", "server_pid", "display_pid") if state.get(k)]
    for pid in pids:
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    deadline = time.time() + 30
    while time.time() < deadline and any(_alive(p) for p in pids):
        time.sleep(0.5)
    for pid in pids:
        if _alive(pid):
            os.kill(pid, signal.SIGKILL)


def _alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("start")
    p.add_argument("evidence")
    p.add_argument("--server", default=os.environ.get("SPLINT_SERVER_DIR", DEFAULT_SERVER))
    p.add_argument("--client", default=os.environ.get("SPLINT_CLIENT_DIR", DEFAULT_CLIENT))
    p.add_argument("--agent", help="path to splint-agent.jar to load into the server JVM")
    p.add_argument("--sandbox", nargs="*", help="SandboxVars overrides, Key=Value")
    p.add_argument("--no-client", action="store_true")
    p.add_argument("--server-heap", default="3g")
    p.add_argument("--client-heap", default="3g")
    p.set_defaults(func=cmd_start)
    for side in ("server", "client"):
        p = sub.add_parser(side)
        p.add_argument("evidence")
        p.add_argument("line", nargs=argparse.REMAINDER)
        p.add_argument("--timeout", type=float, default=60)
        p.set_defaults(func=cmd_send, side=side)
    p = sub.add_parser("console")
    p.add_argument("evidence")
    p.add_argument("line", nargs=argparse.REMAINDER)
    p.set_defaults(func=cmd_console)
    p = sub.add_parser("wait")
    p.add_argument("evidence")
    p.add_argument("side", choices=["server", "client"])
    p.add_argument("text")
    p.add_argument("--timeout", type=float, default=120)
    p.set_defaults(func=cmd_wait)
    p = sub.add_parser("stop")
    p.add_argument("evidence")
    p.set_defaults(func=cmd_stop)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
