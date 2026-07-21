#!/usr/bin/env python3
"""
Build a resource mod JAR from the template.

Usage:
    python pack.py --modid <id> --resources <dir> [--output <jar>]

The mod version is read from build.gradle.
Resource directory structure: assets/ data/ resource-pack/ override/
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import uuid
import zipfile
from pathlib import Path

TEMPLATE_DIR = Path(__file__).parent.resolve()
KNOWN_DIRS = {"assets", "data", "resource-pack", "override"}


def read_template_version(build_dir):
    gf = build_dir / "build.gradle"
    if not gf.is_file():
        sys.exit("build.gradle not found")
    for line in gf.read_text().splitlines():
        line = line.strip()
        if line.startswith("version"):
            v = line.split("=")[-1] if "=" in line else line.split(None, 1)[-1]
            return v.strip().strip("'\"").split("'")[0].split('"')[0]
    return "1.0.0"


def replace_in_tree(root, replacements):
    for path in Path(root).rglob("*"):
        if not path.is_file():
            continue
        try:
            text = path.read_text("utf-8")
        except UnicodeDecodeError:
            continue
        changed = False
        for key, val in replacements.items():
            placeholder = "{{" + key + "}}"
            if placeholder in text:
                text = text.replace(placeholder, val)
                changed = True
        if changed:
            path.write_text(text, "utf-8")
            print(f"  Replaced: {path.relative_to(root)}")


def add_resources(jar_path, resources_dir):
    if not Path(resources_dir).is_dir():
        print(f"Resources directory not found: {resources_dir}")
        return
    with zipfile.ZipFile(jar_path, "a", zipfile.ZIP_DEFLATED) as z:
        existing = set(z.namelist())
        for root, dirs, files in os.walk(resources_dir):
            for fname in files:
                src = Path(root) / fname
                arcname = str(src.relative_to(resources_dir))
                if arcname in existing:
                    continue
                top = arcname.split("/")[0] if "/" in arcname else arcname
                if top not in KNOWN_DIRS:
                    print(f"  SKIP (unknown dir): {arcname}")
                    continue
                z.write(src, arcname)
                print(f"  Added: {arcname}")


def main():
    parser = argparse.ArgumentParser(description="Build a resource mod JAR")
    parser.add_argument("--modid", required=True)
    parser.add_argument("--resources", required=True)
    parser.add_argument("--output")
    args = parser.parse_args()

    # Validate modId
    import re
    if not re.fullmatch(r'^[a-z][a-z0-9_]{1,63}$', args.modid):
        sys.exit("Error: modId must be lowercase, start with a letter, "
                 "and contain only a-z, 0-9, underscore. "
                 "Hyphens ('-') are not allowed. Got: " + args.modid)

    resources = Path(args.resources).resolve()
    if not resources.is_dir():
        sys.exit(f"Resources directory not found: {resources}")

    output = Path(args.output) if args.output else Path(f"{args.modid}.jar")

    with tempfile.TemporaryDirectory() as tmp:
        build_dir = Path(tmp) / "build"
        shutil.copytree(TEMPLATE_DIR, build_dir,
                        ignore=shutil.ignore_patterns(
                            ".gradle", "build", "__pycache__", "*.pyc"))
        print("Copied template to build directory")

        version = read_template_version(build_dir)
        print(f"Mod ID:    {args.modid}")
        print(f"Version:   {version}")
        print(f"Resources: {resources}")

        replace_in_tree(build_dir, {"MODID": args.modid, "VERSION": version})

        print("Building with Gradle...")
        gradlew = TEMPLATE_DIR / ("gradlew.bat" if os.name == "nt" else "gradlew")
        result = subprocess.run(
            [str(gradlew), "-p", str(build_dir), "buildAll",
             "--no-daemon", "-q"],
            capture_output=True, text=True
        )
        if result.returncode != 0:
            print(result.stdout)
            print(result.stderr, file=sys.stderr)
            sys.exit("Gradle build failed")

        # Collect built JARs: fabric + neoforge both include common
        fabric_jar = build_dir / "fabric" / "build" / "libs" / "fabric.jar"
        neoforge_jar = build_dir / "neoforge" / "build" / "libs" / "neoforge.jar"

        if not fabric_jar.is_file() or not neoforge_jar.is_file():
            # Try alternate names
            fabric_jars = list((build_dir / "fabric" / "build" / "libs").glob("*.jar"))
            neoforge_jars = list((build_dir / "neoforge" / "build" / "libs").glob("*.jar"))
            if fabric_jars:
                fabric_jar = fabric_jars[0]
            if neoforge_jars:
                neoforge_jar = neoforge_jars[0]
            if not fabric_jar.is_file() or not neoforge_jar.is_file():
                sys.exit("Built JARs not found")

        print(f"Fabric:   {fabric_jar.name} ({fabric_jar.stat().st_size:,} bytes)")
        print(f"NeoForge: {neoforge_jar.name} ({neoforge_jar.stat().st_size:,} bytes)")

        # Merge: use fabric as base, add neoforge-only entries
        merged = build_dir / "merged.jar"
        shutil.copy2(fabric_jar, merged)

        lock = uuid.uuid4().hex
        with zipfile.ZipFile(merged, "a", zipfile.ZIP_DEFLATED) as z:
            existing = set(z.namelist())
            # Write lock file
            z.writestr(".resource-lock", lock)
            # Add neoforge-only entries
            with zipfile.ZipFile(neoforge_jar, "r") as nz:
                for item in nz.infolist():
                    if item.filename not in existing and not item.is_dir():
                        z.writestr(item, nz.read(item.filename))
        print(f"Lock:      {lock}")

        print("Adding resources...")
        add_resources(merged, resources)

        shutil.copy2(merged, output)

    print(f"\nCreated: {output}")
    print(f"Size:    {output.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
