import json
import os
from typing import Any, Dict, List, Optional

DEFAULT_WORKSPACE_FILE = "manifest-workspace.json"


def init_workspace() -> Dict[str, Any]:
    """Return an empty workspace dictionary."""
    return {"mods": {}}


def load_workspace(path: Optional[str] = None) -> Dict[str, Any]:
    """Load workspace from the JSON file at *path* (defaults to DEFAULT_WORKSPACE_FILE).
    If the file does not exist, return an initialised empty workspace.
    """
    path = path or DEFAULT_WORKSPACE_FILE
    if not os.path.exists(path):
        return init_workspace()
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def save_workspace(workspace: Dict[str, Any], path: Optional[str] = None) -> None:
    """Write *workspace* to the JSON file at *path* (defaults to DEFAULT_WORKSPACE_FILE)."""
    path = path or DEFAULT_WORKSPACE_FILE
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(workspace, fh, indent=2, sort_keys=True)
        fh.write("\n")


def selector_from_values(
    loaders: Optional[List[str]] = None,
    operating_systems: Optional[List[str]] = None,
    architectures: Optional[List[str]] = None,
) -> Dict[str, List[str]]:
    """Create a selector dict that contains only the provided non‑empty lists."""
    sel: Dict[str, List[str]] = {}
    if loaders:
        sel["loaders"] = sorted(loaders)
    if operating_systems:
        sel["operatingSystems"] = sorted(operating_systems)
    if architectures:
        sel["architectures"] = sorted(architectures)
    return sel


def normalize_selector(selector: Dict[str, Any]) -> tuple:
    """Return a hashable normalised representation of *selector*."""
    loaders = frozenset(selector.get("loaders") or ())
    ops = frozenset(selector.get("operatingSystems") or ())
    archs = frozenset(selector.get("architectures") or ())
    return (loaders, ops, archs)


def selector_equal(a: Dict[str, Any], b: Dict[str, Any]) -> bool:
    """Return True if *a* and *b* represent the same selector."""
    return normalize_selector(a) == normalize_selector(b)
