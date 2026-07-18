import json
import os
import tempfile

import pytest

from mcumanifest.workspace import (
    init_workspace,
    load_workspace,
    save_workspace,
    selector_from_values,
    normalize_selector,
    selector_equal,
)


def test_init_workspace_is_empty():
    ws = init_workspace()
    assert ws == {"mods": {}}


def test_load_workspace_non_existent_returns_empty():
    ws = load_workspace("/nonexistent/path/workspace.json")
    assert ws == {"mods": {}}


def test_save_and_load_roundtrip():
    with tempfile.TemporaryDirectory() as td:
        fpath = os.path.join(td, "test-ws.json")
        ws = {
            "mods": {
                "sample": {
                    "name": "sample",
                    "required": True,
                    "variants": [],
                }
            }
        }
        save_workspace(ws, fpath)
        loaded = load_workspace(fpath)
        assert loaded == ws


def test_selector_from_values_only_provided_keys():
    sel = selector_from_values(loaders=["fabric", "neoforge"])
    assert set(sel.keys()) == {"loaders"}
    assert sorted(sel["loaders"]) == ["fabric", "neoforge"]

    sel2 = selector_from_values(architectures=["x86_64"], operating_systems=["linux"])
    assert set(sel2.keys()) == {"architectures", "operatingSystems"}


def test_normalize_selector_ignores_order_and_missing_keys():
    a = {"loaders": ["fabric", "neoforge"], "architectures": ["x86_64"]}
    b = {"loaders": ["neoforge", "fabric"], "architectures": ["x86_64"]}
    c = {"loaders": ["forge"]}
    d = {}
    assert normalize_selector(a) == normalize_selector(b)
    assert normalize_selector(a) != normalize_selector(c)
    assert normalize_selector(d) == normalize_selector({})


def test_selector_equal():
    a = {"loaders": ["fabric"], "operatingSystems": ["windows"]}
    b = {"operatingSystems": ["windows"], "loaders": ["fabric"]}
    c = {"loaders": ["fabric"]}
    assert selector_equal(a, b) is True
    assert selector_equal(a, c) is False
    assert selector_equal({}, {}) is True
