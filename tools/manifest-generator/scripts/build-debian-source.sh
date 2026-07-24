#!/usr/bin/env bash
set -euo pipefail

if ! command -v dpkg-source >/dev/null 2>&1; then
  echo "error: dpkg-source is required (install dpkg-dev)" >&2
  exit 1
fi

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
project_dir="$(CDPATH= cd -- "$script_dir/.." && pwd)"
package_name="mcumanifest"

version="$(
  python3 - "$project_dir/pyproject.toml" <<'PY'
import sys
import tomllib
from pathlib import Path

pyproject = Path(sys.argv[1])
data = tomllib.loads(pyproject.read_text(encoding="utf-8"))
print(data["project"]["version"])
PY
)"

dist_dir="$project_dir/.deb-dist"
build_dir="$project_dir/.deb-build"
source_dir="$build_dir/$package_name-$version"
orig_tarball="$build_dir/${package_name}_${version}.orig.tar.gz"

rm -rf "$dist_dir" "$build_dir"
mkdir -p "$dist_dir" "$source_dir"

cp -a \
  "$project_dir/pyproject.toml" \
  "$project_dir/README.md" \
  "$project_dir/src" \
  "$project_dir/tests" \
  "$source_dir/"

find "$source_dir" \
  -type d \( -name "__pycache__" -o -name ".pytest_cache" \) \
  -prune -exec rm -rf {} +
find "$source_dir" -type f \( -name "*.pyc" -o -name "*.pyo" \) -delete

tar -C "$build_dir" -czf "$orig_tarball" "$package_name-$version"
cp -a "$project_dir/debian" "$source_dir/debian"

(
  cd "$source_dir"
  dpkg-source -b .
)

cp -a "$orig_tarball" "$dist_dir/"
find "$build_dir" -maxdepth 1 -type f \
  \( -name "${package_name}_${version}-*.dsc" \
  -o -name "${package_name}_${version}-*.debian.tar.*" \) \
  -exec cp -a {} "$dist_dir/" \;

echo "Debian source package files written to $dist_dir"
