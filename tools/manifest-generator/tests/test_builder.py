import json
import os
import tempfile

import pytest

from mcumanifest.builder import add_or_update_variant, build_manifest
from mcumanifest.workspace import init_workspace, selector_from_values


def make_variant(local_file, download_type="hosted", selector=None):
    selector = selector or selector_from_values(loaders=["fabric"])
    return {
        "selector": selector,
        "priority": 0,
        "version": "1.0.0",
        "fileName": "mod.jar",
        "localFile": local_file,
        "download": {"type": download_type, "url": "https://example.com/mod.jar"},
    }


@pytest.fixture
def ws():
    return init_workspace()


def test_add_first_variant_returns_added(ws):
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf:
        tf.write(b"hello")
        tf.flush()
        variant = make_variant(tf.name)
        status = add_or_update_variant(ws, "coolmod", selector_from_values(loaders=["fabric"]), variant)
        assert status == "added"
        assert ws["mods"]["coolmod"]["required"] is True
    os.unlink(tf.name)


def test_add_second_variant_different_selector_returns_appended(ws):
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf1, \
         tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf2:
        tf1.write(b"first")
        tf2.write(b"second")
        tf1.flush(); tf2.flush()
        v1 = make_variant(tf1.name, selector=selector_from_values(loaders=["fabric"]))
        status1 = add_or_update_variant(ws, "moda", selector_from_values(loaders=["fabric"]), v1)
        assert status1 == "added"
        v2 = make_variant(tf2.name, selector=selector_from_values(loaders=["neoforge"]))
        status2 = add_or_update_variant(ws, "moda", selector_from_values(loaders=["neoforge"]), v2)
        assert status2 == "appended"
    os.unlink(tf1.name)
    os.unlink(tf2.name)


def test_conflict_on_same_selector_no_force(ws):
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf:
        tf.write(b"data")
        tf.flush()
        sel = selector_from_values(loaders=["fabric"])
        v1 = make_variant(tf.name, selector=sel)
        add_or_update_variant(ws, "modc", sel, v1)
        status = add_or_update_variant(ws, "modc", sel, v1)
        assert status == "conflict"
    os.unlink(tf.name)


def test_force_updates_existing_variant(ws):
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf1, \
         tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf2:
        tf1.write(b"old")
        tf2.write(b"new")
        tf1.flush(); tf2.flush()
        sel = selector_from_values(loaders=["fabric"])
        v1 = make_variant(tf1.name, selector=sel)
        add_or_update_variant(ws, "modd", sel, v1)
        v2 = make_variant(tf2.name, selector=sel)
        status = add_or_update_variant(ws, "modd", sel, v2, force=True)
        assert status == "updated"
        # Verify the stored variant is v2 (check localFile)
        stored = ws["mods"]["modd"]["variants"][0]
        assert stored["localFile"] == tf2.name
    os.unlink(tf1.name); os.unlink(tf2.name)


def test_no_overwrite_causes_conflict(ws):
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf:
        tf.write(b"test")
        tf.flush()
        sel = selector_from_values(loaders=["fabric"])
        v = make_variant(tf.name, selector=sel)
        add_or_update_variant(ws, "mode", sel, v)
        status = add_or_update_variant(ws, "mode", sel, v, no_overwrite=True)
        assert status == "conflict"
    os.unlink(tf.name)


def test_build_manifest_with_sha_hashes(ws):
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf:
        content = b"sample jar content"
        tf.write(content)
        tf.flush()
        variant = make_variant(tf.name, download_type="hosted")
        ws["mods"] = {
            "testmod": {
                "name": "Test Mod",
                "required": True,
                "license": "mit",
                "variants": [variant],
            }
        }
        ws["minecraftVersion"] = "1.21.1"
        manifest = build_manifest(ws, allow_redistribution=False)
        mod = manifest["mods"]["testmod"]
        assert mod["name"] == "Test Mod"
        assert mod["required"] is True
        variant_built = mod["variants"][0]
        artifact = variant_built["artifact"]
        assert artifact["size"] == len(content)
        # Only assert the expected lengths – the exact hash values depend on the content
        assert len(artifact["hashes"]["sha256"]) == 64
        assert len(artifact["hashes"]["sha512"]) == 128
        assert artifact["version"] == "1.0.0"
    os.unlink(tf.name)


def test_build_missing_local_file_raises_value_error(ws):
    ws["mods"] = {
        "badmod": {
            "name": "bad",
            "required": False,
            "variants": [{"localFile": "/nonexistent/path.jar", "download": {"type": "hosted", "url": "x"}}]
        }
    }
    with pytest.raises(ValueError, match="Missing local file for mod 'badmod'"):
        build_manifest(ws)


def test_build_license_denied_hosted_raises():
    ws = init_workspace()
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf:
        tf.write(b"content")
        tf.flush()
        variant = make_variant(tf.name, download_type="hosted")
        ws["mods"] = {
            "proprietary": {
                "name": "Prop",
                "required": True,
                "license": "ARR",
                "variants": [variant],
            }
        }
        with pytest.raises(ValueError, match="does not allow hosted"):
            build_manifest(ws, allow_redistribution=False)
    os.unlink(tf.name)


def test_build_license_allows_with_flag():
    ws = init_workspace()
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf:
        tf.write(b"value")
        tf.flush()
        variant = make_variant(tf.name, download_type="hosted")
        ws["mods"] = {
            "allowed": {
                "name": "Allowed",
                "required": False,
                "license": "ARR",
                "variants": [variant],
            }
        }
        # should succeed with allow_redistribution=True
        manifest = build_manifest(ws, allow_redistribution=True)
        assert "allowed" in manifest["mods"]
    os.unlink(tf.name)


def test_build_per_mod_allow_redistribution_overrides_global(ws):
    """Hosted download for ARR license succeeds when the mod entry has allowRedistribution=true,
    even if the global parameter is False."""
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf:
        tf.write(b"value")
        tf.flush()
        variant = make_variant(tf.name, download_type="hosted")
        ws["mods"] = {
            "permod": {
                "name": "PerMod",
                "required": True,
                "license": "ARR",
                "allowRedistribution": True,
                "variants": [variant],
            }
        }
        manifest = build_manifest(ws, allow_redistribution=False)
        assert "permod" in manifest["mods"]
    os.unlink(tf.name)


def test_build_manifest_includes_base_url_when_present(ws):
    ws["baseUrl"] = "https://dl.example.com"
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf:
        tf.write(b"base content")
        tf.flush()
        variant = make_variant(tf.name, download_type="hosted")
        ws["mods"] = {
            "testmod": {
                "name": "Test",
                "required": True,
                "license": "mit",
                "variants": [variant],
            }
        }
        ws["minecraftVersion"] = "1.21.1"
        manifest = build_manifest(ws, allow_redistribution=False)
        assert manifest["baseUrl"] == "https://dl.example.com"
    os.unlink(tf.name)


def test_build_manifest_no_base_url_when_absent(ws):
    ws.pop("baseUrl", None)
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf:
        tf.write(b"content")
        tf.flush()
        variant = make_variant(tf.name, download_type="hosted")
        ws["mods"] = {
            "testmod": {
                "name": "Test",
                "required": True,
                "license": "mit",
                "variants": [variant],
            }
        }
        ws["minecraftVersion"] = "1.21.1"
        manifest = build_manifest(ws)
        assert "baseUrl" not in manifest
    os.unlink(tf.name)


def test_build_manifest_direct_has_provider_metadata(ws):
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf:
        tf.write(b"provider jar")
        tf.flush()
        sel = selector_from_values(loaders=["fabric"])
        variant = {
            "selector": sel,
            "priority": 0,
            "version": "2.0",
            "fileName": "prov.jar",
            "localFile": tf.name,
            "download": {
                "type": "direct",
                "url": "https://cdn.example.com/mod.jar",
                "provider": "modrinth",
                "projectId": "abc123",
                "versionId": "def456",
            },
        }
        ws["mods"] = {
            "provmod": {
                "name": "ProviderMod",
                "required": True,
                "license": "mit",
                "variants": [variant],
            }
        }
        ws["minecraftVersion"] = "1.21.1"
        manifest = build_manifest(ws)
        mod = manifest["mods"]["provmod"]
        built_variant = mod["variants"][0]
        artifact = built_variant["artifact"]
        dl = artifact["download"]
        assert dl["type"] == "direct"
        assert dl["url"] == "https://cdn.example.com/mod.jar"
        assert dl["provider"] == "modrinth"
        assert dl["projectId"] == "abc123"
        assert dl["versionId"] == "def456"
    os.unlink(tf.name)
