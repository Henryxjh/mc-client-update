import tempfile

import pytest

from mcumanifest.builder import add_or_update_variant
from mcumanifest.workspace import init_workspace, selector_from_values


def make_variant(local_file, selector=None):
    selector = selector or selector_from_values(loaders=["fabric"])
    return {
        "selector": selector,
        "version": "1.0",
        "localFile": local_file,
        "download": {"type": "hosted", "url": "https://example.com/f.jar"},
    }


def test_duplicate_selector_conflicted(monkeypatch):
    ws = init_workspace()
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf:
        tf.write(b"hello")
        tf.flush()
        var = make_variant(tf.name)
        sel = selector_from_values(loaders=["fabric"])
        add_or_update_variant(ws, "dup", sel, var)
        status = add_or_update_variant(ws, "dup", sel, var)
        assert status == "conflict"
    assert len(ws["mods"]["dup"]["variants"]) == 1


def test_different_selector_no_conflict_adds_variant():
    ws = init_workspace()
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf1, \
         tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf2:
        tf1.write(b"a")
        tf2.write(b"b")
        sel1 = selector_from_values(loaders=["fabric"])
        sel2 = selector_from_values(loaders=["neoforge"])
        add_or_update_variant(ws, "multi", sel1, make_variant(tf1.name))
        status = add_or_update_variant(ws, "multi", sel2, make_variant(tf2.name))
        assert status == "appended"
        assert len(ws["mods"]["multi"]["variants"]) == 2
