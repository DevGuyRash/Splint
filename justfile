# Splint tasks. Run `just` to list them. Release logic lives in tools/release.py.

set shell := ["bash", "-euo", "pipefail", "-c"]

# List the recipes
default:
    @just --list

# Build dist/splint.jar against the installed game
build:
    python3 build.py

# Unit tests: the Java agent and the release tooling
test:
    python3 build.py --test
    python3 -m unittest discover -s tools -p 'test_*.py'

# What every release needs: unit tests, offline patch verification, in-game agent check (never skipped)
verify:
    python3 build.py --test --verify --strict
    python3 -m unittest discover -s tools -p 'test_*.py'

# Set the version in the source and commit it as `chore(release): VERSION`
bump version:
    python3 tools/release.py bump {{version}}
    git commit --quiet -m "chore(release): {{version}}" agent/src/splint/core/Splint.java
    git log -1 --oneline

# Print the release notes for VERSION (Conventional Commits since the previous tag)
notes version:
    python3 tools/release.py notes {{version}}

# Stage a release in build/release/vVERSION without publishing anything
package version: verify
    python3 tools/release.py package {{version}}

# Cut a versioned release: checks, full verify, package, signed tag, GitHub release, re-download check
release version:
    python3 tools/release.py check {{version}}
    just package {{version}}
    python3 tools/release.py publish {{version}}

# Rebuild and replace the assets of an existing release (same agent source as its tag only)
assets version:
    just package {{version}}
    python3 tools/release.py upload {{version}}
