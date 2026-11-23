"""Tests for tools/release.py against throwaway git repositories (no network, no signing).

Run: python3 -m unittest discover -s tools -p 'test_*.py'   (part of `just test` and `just verify`)
"""

from __future__ import annotations

import hashlib
import importlib.util
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

_spec = importlib.util.spec_from_file_location("release", Path(__file__).with_name("release.py"))
assert _spec and _spec.loader
release = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(release)


def sh(cwd: Path, *args: str) -> str:
    return subprocess.run(args, cwd=cwd, check=True, capture_output=True, text=True).stdout


class Repo:
    """A local repository on main with signing off, so tests never prompt."""

    def __init__(self, root: Path):
        self.root = root
        sh(root, "git", "init", "--quiet", "--initial-branch=main")
        for k, v in [("user.name", "Test"), ("user.email", "test@example.invalid"),
                     ("commit.gpgsign", "false"), ("tag.gpgsign", "false")]:
            sh(root, "git", "config", k, v)

    def commit(self, subject: str, body: str = "", path: str = "file.txt") -> None:
        f = self.root / path
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(f.read_text() + subject + "\n" if f.exists() else subject + "\n")
        sh(self.root, "git", "add", path)
        sh(self.root, "git", "commit", "--quiet", "-m", subject, *(["-m", body] if body else []))

    def tag(self, name: str) -> None:
        sh(self.root, "git", "tag", name)


def jar(path: Path, version: str | None) -> Path:
    with zipfile.ZipFile(path, "w") as z:
        manifest = "Manifest-Version: 1.0\n" + (f"Implementation-Version: {version}\n" if version else "")
        z.writestr("META-INF/MANIFEST.MF", manifest)
    return path


class Temp(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()


class NotesTest(Temp):
    def test_groups_conventional_commits_since_previous_tag(self):
        r = Repo(self.tmp)
        r.commit("feat: before the last release")
        r.tag("v0.2.0")
        r.commit("feat(animals): kill by life stage")
        r.commit("fix: ghosts are removed before unload")
        r.commit("docs: install guide")
        r.commit("build: release recipe")
        r.commit("Update stuff")
        r.commit("feat!: require Java 25")
        r.commit("refactor(core): new breaker", "BREAKING CHANGE: Breaker API changed")
        r.commit("chore(release): 0.3.0")
        text = release.notes("0.3.0", cwd=self.tmp)
        sections = [line for line in text.splitlines() if line.startswith("### ")]
        self.assertEqual(sections, ["### Breaking changes", "### Features", "### Fixes", "### Documentation",
                                    "### Maintenance", "### Other changes"])
        self.assertIn("- **animals:** kill by life stage (", text)
        self.assertIn("- require Java 25 (", text)
        self.assertIn("- **core:** new breaker (", text)
        self.assertIn("- release recipe (", text)
        self.assertIn("- Update stuff (", text)
        self.assertNotIn("before the last release", text)
        self.assertNotIn("0.3.0 (", text)                     # the release commit itself is left out
        self.assertEqual(text.count("require Java 25"), 1)    # breaking changes are listed once
        self.assertTrue(text.rstrip().endswith("Changes: v0.2.0...v0.3.0"))

    def test_first_release_has_no_previous_tag(self):
        r = Repo(self.tmp)
        r.commit("feat: first")
        text = release.notes("0.1.0", cwd=self.tmp)
        self.assertIn("### Features\n- first (", text)
        self.assertTrue(text.rstrip().endswith("First release."))


class CheckTest(Temp):
    def setUp(self):
        super().setUp()
        self.repo = Repo(self.tmp)
        self.repo.commit("feat: something")

    def problems(self, version="0.3.0", source="0.3.0"):
        return release.problems(version, cwd=self.tmp, source_version=source, remote=False)

    def test_clean_main_with_matching_version_passes(self):
        self.assertEqual(self.problems(), [])

    def test_each_reason_to_refuse(self):
        self.assertTrue(any("just bump 0.3.0" in p for p in self.problems(source="0.2.0")))
        self.assertTrue(any("not a semantic version" in p for p in self.problems(version="v0.3", source="v0.3")))
        (self.tmp / "file.txt").write_text("dirty\n")
        self.assertTrue(any("uncommitted" in p for p in self.problems()))
        sh(self.tmp, "git", "checkout", "--quiet", "--", "file.txt")
        self.repo.tag("v0.3.0")
        self.assertTrue(any("already exists" in p for p in self.problems()))
        sh(self.tmp, "git", "checkout", "--quiet", "-b", "dev")
        self.assertTrue(any("cut from main" in p for p in self.problems(version="0.4.0", source="0.4.0")))


class PackageTest(Temp):
    def setUp(self):
        super().setUp()
        self.dist = self.tmp / "dist"
        self.dist.mkdir()
        self.install = self.tmp / "INSTALL.md"
        self.install.write_text("# Installing\n")

    def package(self, version="0.3.0"):
        return release.package(version, dist=self.dist, stage_root=self.tmp / "stage", install=self.install,
                               notes_text="notes\n")

    def test_every_dist_file_ships_with_checksums_main_jar_first(self):
        jar(self.dist / "zeta.jar", "0.3.0")
        jar(self.dist / "splint.jar", "0.3.0")
        jar(self.dist / "alpha.jar", None)
        stage = self.package()
        self.assertEqual([p.name for p in release.assets(stage)],
                         ["splint.jar", "alpha.jar", "zeta.jar", "INSTALL.md", "SHA256SUMS"])
        sums = {}
        for line in (stage / "SHA256SUMS").read_text().splitlines():
            digest, name = line.split("  ")
            sums[name] = digest
        self.assertEqual(set(sums), {"splint.jar", "alpha.jar", "zeta.jar", "INSTALL.md"})
        self.assertEqual(sums["INSTALL.md"], hashlib.sha256(b"# Installing\n").hexdigest())
        self.assertEqual((stage / "NOTES.md").read_text(), "notes\n")
        self.assertEqual(release.verify_sums(stage), [])

    def test_a_jar_built_for_another_version_is_refused(self):
        jar(self.dist / "splint.jar", "0.2.0")
        with self.assertRaises(release.ReleaseError) as e:
            self.package()
        self.assertIn("built as 0.2.0, not 0.3.0", str(e.exception))

    def test_empty_dist_is_refused(self):
        with self.assertRaises(release.ReleaseError):
            self.package()

    def test_tampering_after_packaging_is_caught(self):
        jar(self.dist / "splint.jar", "0.3.0")
        stage = self.package()
        (stage / "splint.jar").write_bytes(b"not the jar")
        self.assertEqual(release.verify_sums(stage), ["splint.jar does not match its checksum"])
        (stage / "INSTALL.md").unlink()
        self.assertIn("INSTALL.md is listed but missing", release.verify_sums(stage))


class UploadTest(Temp):
    def test_assets_may_be_replaced_only_while_agent_source_matches_the_tag(self):
        r = Repo(self.tmp)
        r.commit("feat: agent", path="agent/src/A.java")
        self.assertIn("does not exist", release.upload_problems("0.3.0", cwd=self.tmp)[0])
        r.tag("v0.3.0")
        r.commit("docs: readme", path="README.md")
        self.assertEqual(release.upload_problems("0.3.0", cwd=self.tmp), [])
        r.commit("fix: agent change", path="agent/src/A.java")
        found = release.upload_problems("0.3.0", cwd=self.tmp)
        self.assertEqual(len(found), 1)
        self.assertIn("agent/src/A.java", found[0])


if __name__ == "__main__":
    unittest.main()
