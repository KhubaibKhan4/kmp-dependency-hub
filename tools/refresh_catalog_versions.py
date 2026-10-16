#!/usr/bin/env python3
"""Updates every version in core/src/main/resources/kmphub/catalog.json to the latest stable release.

Looks each versionRef up through the first artifact (or plugin marker) that uses it, on Maven Central,
Google Maven and the Gradle Plugin Portal. Run from the repo root:  python3 tools/refresh_catalog_versions.py
"""
import json
import re
import sys
import urllib.request
from pathlib import Path

CATALOG = Path(__file__).resolve().parent.parent / "core/src/main/resources/kmphub/catalog.json"
REPOS = [
    "https://repo1.maven.org/maven2",
    "https://dl.google.com/dl/android/maven2",
    "https://plugins.gradle.org/m2",
]
# Versions that track a toolchain rather than one of the library's own artifacts.
SPECIAL = {
    "kotlin": "org.jetbrains.kotlin:kotlin-stdlib",
    "ksp": "com.google.devtools.ksp:symbol-processing-api",
}
UNSTABLE = re.compile(r"(?i)(alpha|beta|rc|dev|eap|snapshot|-m\d|compat)")


def key(version):
    return [(0, int(p)) if p.isdigit() else (-1, p) for p in re.split(r"[.\-]", version)]


def latest_stable(module):
    group, name = module.split(":")
    for repo in REPOS:
        url = f"{repo}/{group.replace('.', '/')}/{name}/maven-metadata.xml"
        try:
            with urllib.request.urlopen(url, timeout=20) as response:
                xml = response.read().decode()
        except Exception:
            continue
        versions = [v for v in re.findall(r"<version>([^<]+)</version>", xml) if not UNSTABLE.search(v)]
        if versions:
            return max(versions, key=key)
    return None


def main():
    data = json.loads(CATALOG.read_text())
    cache = {}
    changed = 0
    for lib in data["libraries"]:
        for ref, current in lib["versions"].items():
            module = SPECIAL.get(ref)
            if module is None:
                module = next((a["module"] for a in lib["artifacts"] if a.get("versionRef") == ref), None)
            if module is None:
                plugin = next((p["id"] for p in lib.get("plugins", []) if p["versionRef"] == ref), None)
                module = plugin and f"{plugin}:{plugin}.gradle.plugin"
            if module is None:
                continue
            if module not in cache:
                cache[module] = latest_stable(module)
            latest = cache[module]
            if latest is None:
                print(f"  ? {lib['id']}.{ref}: could not resolve {module}", file=sys.stderr)
            elif latest != current:
                print(f"  {lib['id']}.{ref}: {current} -> {latest}")
                lib["versions"][ref] = latest
                changed += 1
    CATALOG.write_text(json.dumps(data, indent=2) + "\n")
    print(f"{changed} version(s) updated")


if __name__ == "__main__":
    main()
