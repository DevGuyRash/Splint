#!/usr/bin/env python3
"""Versioned releases of Splint. The justfile calls this; run `just --list` for the recipes.

    release.py check VERSION      refuse unless VERSION can be released from this checkout
    release.py package VERSION    stage dist/ artifacts + INSTALL.md + SHA256SUMS + NOTES.md
    release.py publish VERSION    signed tag, push it, create the GitHub release from the staged files
    release.py upload VERSION     replace the assets of the existing VERSION release with the staged files
    release.py published VERSION  download the published release and check it against SHA256SUMS
    release.py notes VERSION      print the release notes (Conventional Commits since the last tag)
    release.py bump VERSION       set the version in the source (Splint.VERSION)

Artifacts are generic: every file build.py leaves in dist/ ships, splint.jar first. A jar
whose manifest names an Implementation-Version must name VERSION, so a stale or mismatched
build cannot be released. INSTALL.md is the repository's own install guide, attached as is.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def _load_build():
    """build.py by path: `import build` could pick up the unrelated PyPI package of that name."""
    spec = importlib.util.spec_from_file_location("splint_build", ROOT / "build.py")
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


build = _load_build()  # the version's single source of truth

DIST = ROOT / "dist"
STAGE = ROOT / "build/release"
INSTALL = ROOT / "INSTALL.md"
FIRST = "splint.jar"
SEMVER = re.compile(r"^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$")
CONVENTIONAL = re.compile(r"^(?P<type>[a-z]+)(?:\((?P<scope>[^)]+)\))?(?P<bang>!)?: (?P<desc>.+)$")
# Release-note sections in order; types not listed go to "Maintenance".
SECTIONS = [("feat", "Features"), ("fix", "Fixes"), ("perf", "Performance"), ("docs", "Documentation")]


class ReleaseError(SystemExit):
    pass


def git(*args: str, cwd: Path = ROOT) -> str:
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def tag(version: str) -> str:
    return f"v{version}"


def require_semver(version: str) -> None:
    if not SEMVER.match(version):
        raise ReleaseError(f"'{version}' is not a semantic version like 1.2.3 or 1.2.3-rc.1")


# ---- check -----------------------------------------------------------------------------

def problems(version: str, cwd: Path = ROOT, source_version: str | None = None, remote: bool = True) -> list[str]:
    """Every reason VERSION cannot be released from the checkout at cwd (empty = good to go)."""
    out = []
    if not SEMVER.match(version):
        out.append(f"'{version}' is not a semantic version like 1.2.3")
    source = source_version if source_version is not None else build.version()
    if source != version:
        out.append(f"the source says {source}; run `just bump {version}` and commit it first")
    branch = git("branch", "--show-current", cwd=cwd)
    if branch != "main":
        out.append(f"releases are cut from main, not '{branch or 'a detached HEAD'}'")
    if git("status", "--porcelain", cwd=cwd):
        out.append("the working tree has uncommitted changes")
    if tag(version) in git("tag", "--list", tag(version), cwd=cwd).split():
        out.append(f"tag {tag(version)} already exists")
    if remote:
        git("fetch", "--quiet", "--tags", "origin", cwd=cwd)
        if git("rev-parse", "HEAD", cwd=cwd) != git("rev-parse", "origin/main", cwd=cwd):
            out.append("main is not in sync with origin/main (push or pull first)")
        if git("ls-remote", "--tags", "origin", f"refs/tags/{tag(version)}", cwd=cwd):
            out.append(f"tag {tag(version)} already exists on origin")
    return out


def check(version: str) -> None:
    found = problems(version)
    if found:
        raise ReleaseError("cannot release " + version + ":\n  - " + "\n  - ".join(found))
    print(f"ok: {version} can be released from {git('rev-parse', '--short', 'HEAD')}")


# ---- notes -----------------------------------------------------------------------------

def previous_tag(version: str, cwd: Path = ROOT) -> str | None:
    """The newest release tag reachable from HEAD other than VERSION's own."""
    tags = git("tag", "--list", "v*", "--sort=-v:refname", "--merged", "HEAD", cwd=cwd).split()
    return next((t for t in tags if t != tag(version) and SEMVER.match(t[1:])), None)


def commits(since: str | None, until: str, cwd: Path = ROOT) -> list[tuple[str, str, str]]:
    """(short hash, subject, body) of every commit in since..until, oldest first."""
    span = f"{since}..{until}" if since else until
    # Not git(): str.strip() counts the \x1e/\x1f separators as whitespace and would eat them.
    raw = subprocess.run(["git", "log", "--reverse", "--format=%h%x1f%s%x1f%b%x1e", span], cwd=cwd,
                         check=True, capture_output=True, text=True).stdout
    out = []
    for record in raw.split("\x1e"):
        record = record.lstrip("\n")
        if record:
            short, subject, body = record.split("\x1f", 2)
            out.append((short, subject, body))
    return out


def notes(version: str, cwd: Path = ROOT, until: str = "HEAD") -> str:
    since = previous_tag(version, cwd)
    groups: dict[str, list[str]] = {}
    breaking: list[str] = []
    for short, subject, body in commits(since, until, cwd):
        m = CONVENTIONAL.match(subject)
        if not m:
            groups.setdefault("Other changes", []).append(f"- {subject} ({short})")
            continue
        if m["type"] == "chore" and m["scope"] == "release":
            continue
        scope = f"**{m['scope']}:** " if m["scope"] else ""
        line = f"- {scope}{m['desc']} ({short})"
        if m["bang"] or "BREAKING CHANGE:" in body:
            breaking.append(line)
            continue
        title = dict(SECTIONS).get(m["type"], "Maintenance")
        groups.setdefault(title, []).append(line)
    out = [f"Install: download `{FIRST}` and follow the attached `INSTALL.md`. "
           "Check the download with `sha256sum -c SHA256SUMS`.", ""]
    if breaking:
        out += ["### Breaking changes", *breaking, ""]
    for title in [t for _, t in SECTIONS] + ["Maintenance", "Other changes"]:
        if groups.get(title):
            out += [f"### {title}", *groups[title], ""]
    span = f"{since}...{tag(version)}" if since else tag(version)
    out.append(f"Changes: {span}" if since else "First release.")
    return "\n".join(out).rstrip() + "\n"


# ---- package ---------------------------------------------------------------------------

def artifacts(dist: Path = DIST) -> list[Path]:
    files = sorted((p for p in dist.iterdir() if p.is_file()), key=lambda p: (p.name != FIRST, p.name)) \
        if dist.is_dir() else []
    if not files:
        raise ReleaseError(f"no artifacts in {dist}; run `just verify` first")
    return files


def jar_version(path: Path) -> str | None:
    if path.suffix != ".jar":
        return None
    with zipfile.ZipFile(path) as z:
        for line in z.read("META-INF/MANIFEST.MF").decode().splitlines():
            if line.startswith("Implementation-Version:"):
                return line.split(":", 1)[1].strip()
    return None


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def package(version: str, dist: Path = DIST, stage_root: Path = STAGE, install: Path = INSTALL,
            notes_text: str | None = None) -> Path:
    """Stages the release files; returns the folder. NOTES.md is the body, not an asset."""
    require_semver(version)
    files = artifacts(dist)
    for f in files:
        v = jar_version(f)
        if v is not None and v != version:
            raise ReleaseError(f"{f.name} was built as {v}, not {version}; rebuild with `just verify`")
    if not install.is_file():
        raise ReleaseError(f"{install} is missing")
    stage = stage_root / tag(version)
    if stage.exists():
        for old in stage.iterdir():
            old.unlink()
    stage.mkdir(parents=True, exist_ok=True)
    shipped = []
    for f in [*files, install]:
        (stage / f.name).write_bytes(f.read_bytes())
        shipped.append(stage / f.name)
    (stage / "SHA256SUMS").write_text("".join(f"{sha256(p)}  {p.name}\n" for p in shipped))
    (stage / "NOTES.md").write_text(notes_text if notes_text is not None else notes(version))
    print(f"staged {tag(version)} in {stage.relative_to(ROOT) if stage.is_relative_to(ROOT) else stage}:")
    for p in assets(stage):
        print(f"  {p.name}")
    return stage


def assets(stage: Path) -> list[Path]:
    """Files to upload: artifacts first, then INSTALL.md, then SHA256SUMS."""
    order = {"INSTALL.md": 1, "SHA256SUMS": 2}
    return sorted((p for p in stage.iterdir() if p.name != "NOTES.md"),
                  key=lambda p: (order.get(p.name, 0), p.name != FIRST, p.name))


# ---- published -------------------------------------------------------------------------

def verify_sums(folder: Path) -> list[str]:
    """Problems with folder's files against its SHA256SUMS (empty = all match)."""
    sums = folder / "SHA256SUMS"
    if not sums.is_file():
        return ["SHA256SUMS is missing"]
    out = []
    for line in sums.read_text().splitlines():
        digest, name = line.split(maxsplit=1)
        f = folder / name.lstrip("*")
        if not f.is_file():
            out.append(f"{name} is listed but missing")
        elif sha256(f) != digest:
            out.append(f"{name} does not match its checksum")
    return out


def published(version: str) -> None:
    require_semver(version)
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["gh", "release", "download", tag(version), "--dir", tmp], cwd=ROOT, check=True)
        found = verify_sums(Path(tmp))
        names = sorted(p.name for p in Path(tmp).iterdir())
        if "INSTALL.md" not in names:
            found.append("INSTALL.md is not attached")
        if not any(n.endswith(".jar") for n in names):
            found.append("no jar is attached")
        if found:
            raise ReleaseError(f"published {tag(version)} is wrong:\n  - " + "\n  - ".join(found))
        print(f"ok: {tag(version)} on GitHub has {', '.join(names)}; every checksum matches")


# ---- publish / upload ------------------------------------------------------------------

def staged(version: str) -> Path:
    stage = STAGE / tag(version)
    if not (stage / "SHA256SUMS").is_file():
        raise ReleaseError(f"nothing staged for {tag(version)}; run `release.py package {version}` first")
    found = verify_sums(stage)
    if found:
        raise ReleaseError("staged files changed after packaging:\n  - " + "\n  - ".join(found))
    return stage


def publish(version: str) -> None:
    """Signed tag (git's own signing setup, e.g. 1Password), push, GitHub release, re-download check."""
    stage = staged(version)
    git("tag", "-s", tag(version), "-m", f"Splint {version}")
    git("push", "--quiet", "origin", tag(version))
    cmd = ["gh", "release", "create", tag(version), "--verify-tag", "--title", f"Splint {version}",
           "--notes-file", str(stage / "NOTES.md")]
    if "-" in version:
        cmd.append("--prerelease")
    subprocess.run([*cmd, *map(str, assets(stage))], cwd=ROOT, check=True)
    published(version)


def upload_problems(version: str, cwd: Path = ROOT) -> list[str]:
    """Reasons the current build may not stand in for the existing VERSION release."""
    if not git("tag", "--list", tag(version), cwd=cwd):
        return [f"tag {tag(version)} does not exist; use `just release {version}` for a new release"]
    out = []
    if git("status", "--porcelain", cwd=cwd):
        out.append("the working tree has uncommitted changes")
    changed = git("diff", "--name-only", tag(version), "HEAD", "--", "agent", cwd=cwd)
    if changed:
        out.append(f"the agent source changed since {tag(version)}, so this build is not {version}: "
                   + ", ".join(changed.splitlines()))
    return out


def upload(version: str) -> None:
    found = upload_problems(version)
    if found:
        raise ReleaseError(f"cannot replace the assets of {tag(version)}:\n  - " + "\n  - ".join(found))
    stage = staged(version)
    subprocess.run(["gh", "release", "upload", tag(version), "--clobber", *map(str, assets(stage))],
                   cwd=ROOT, check=True)
    published(version)


# ---- bump ------------------------------------------------------------------------------

def bump(version: str) -> None:
    require_semver(version)
    text = build.VERSION_FILE.read_text()
    new, n = re.subn(r'(VERSION = ")[^"]+(")', rf"\g<1>{version}\g<2>", text, count=1)
    if n != 1:
        raise ReleaseError(f"no VERSION constant in {build.VERSION_FILE}")
    build.VERSION_FILE.write_text(new)
    print(f"version set to {version} in {build.VERSION_FILE.relative_to(ROOT)}")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    actions = {"check": check, "package": package, "publish": publish, "upload": upload,
               "published": published, "bump": bump}
    p.add_argument("action", choices=[*actions, "notes"])
    p.add_argument("version")
    a = p.parse_args()
    if a.action == "notes":
        sys.stdout.write(notes(a.version, until=tag(a.version) if git("tag", "--list", tag(a.version)) else "HEAD"))
    else:
        actions[a.action](a.version)


if __name__ == "__main__":
    main()
