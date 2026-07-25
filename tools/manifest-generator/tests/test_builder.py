import hashlib
import http.server
import json
import os
import tempfile
import threading

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


def test_build_manifest_includes_skip_if_installed_version_greater_than(ws):
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf:
        tf.write(b"content")
        tf.flush()
        variant = make_variant(tf.name, download_type="hosted")
        ws["mods"] = {
            "coolmod": {
                "name": "Cool",
                "required": True,
                "license": "mit",
                "skipIfInstalledVersionGreaterThan": "1.2.3",
                "variants": [variant],
            }
        }
        ws["minecraftVersion"] = "1.21.1"
        manifest = build_manifest(ws)
        mod = manifest["mods"]["coolmod"]
        assert mod["skipIfInstalledVersionGreaterThan"] == "1.2.3"
    os.unlink(tf.name)


def test_build_rejects_blank_skip_if_installed_version_greater_than(ws):
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf:
        tf.write(b"content")
        tf.flush()
        variant = make_variant(tf.name, download_type="hosted")
        ws["mods"] = {
            "badmod": {
                "name": "Bad",
                "required": False,
                "license": "mit",
                "skipIfInstalledVersionGreaterThan": "   ",
                "variants": [variant],
            }
        }
        ws["minecraftVersion"] = "1.21.1"
        with pytest.raises(ValueError, match="must not be blank"):
            build_manifest(ws)
    os.unlink(tf.name)


# ---------------------------------------------------------------------------
# Tests for direct-download without local file
# ---------------------------------------------------------------------------

class _QuietStaticHandler(http.server.SimpleHTTPRequestHandler):
    """A quiet handler that serves files from a given directory."""

    def __init__(self, *args, directory=None, **kwargs):
        super().__init__(*args, directory=directory, **kwargs)

    def log_message(self, format, *args):
        # suppress access logs in tests
        pass


@pytest.fixture
def direct_http_server(tmp_path):
    """Start a local HTTP server serving a jar file. Yields (url, server, content)."""
    import functools

    jar_dir = tmp_path / "files"
    jar_dir.mkdir()
    content = b"hello-from-server"
    jar_path = jar_dir / "mod.jar"
    jar_path.write_bytes(content)

    server = http.server.HTTPServer(
        ("127.0.0.1", 0),
        functools.partial(_QuietStaticHandler, directory=jar_dir),
    )
    port = server.server_address[1]
    url = f"http://127.0.0.1:{port}/mod.jar"

    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    yield url, server, content

    server.shutdown()
    thread.join(timeout=2)


def test_build_direct_download_no_local_file(ws, direct_http_server):
    url, server, content = direct_http_server

    variant = {
        "selector": {},
        "priority": 0,
        "version": "1.0",
        "localFile": None,
        "fileName": "",  # will be inferred
        "download": {"type": "direct", "url": url},
    }
    ws["mods"] = {
        "testdirect": {
            "name": "Test Direct",
            "required": True,
            "license": "mit",
            "variants": [variant],
        }
    }
    ws["minecraftVersion"] = "1.21.1"

    manifest = build_manifest(ws)
    mod = manifest["mods"]["testdirect"]
    built_variant = mod["variants"][0]
    artifact = built_variant["artifact"]

    assert artifact["size"] == len(content)
    assert artifact["hashes"]["sha256"] == hashlib.sha256(content).hexdigest()
    assert artifact["hashes"]["sha512"] == hashlib.sha512(content).hexdigest()
    assert artifact["fileName"] == "mod.jar"

    # ensure localFile does NOT leak into the manifest
    assert "localFile" not in built_variant
    assert "localFile" not in artifact
    assert "localFile" not in manifest["mods"]["testdirect"]


def test_build_direct_download_uses_workspace_url_rewrite(ws, direct_http_server):
    local_url, server, content = direct_http_server
    local_prefix = local_url.rsplit("/", 1)[0] + "/"
    original_url = "https://cdn.example.com/mirror/mod.jar"
    ws["buildDownloadOverrides"] = {
        "urlRewrites": [
            {"from": "https://cdn.example.com/mirror/", "to": local_prefix},
        ]
    }
    ws["mods"] = {
        "rewritten": {
            "name": "Rewritten",
            "required": True,
            "license": "mit",
            "variants": [
                {
                    "selector": {},
                    "priority": 0,
                    "version": "1.0",
                    "localFile": None,
                    "fileName": "mod.jar",
                    "download": {"type": "direct", "url": original_url},
                }
            ],
        }
    }
    ws["minecraftVersion"] = "1.21.1"

    events = []
    manifest = build_manifest(ws, progress_callback=events.append)
    artifact = manifest["mods"]["rewritten"]["variants"][0]["artifact"]

    assert artifact["size"] == len(content)
    assert artifact["hashes"]["sha256"] == hashlib.sha256(content).hexdigest()
    assert artifact["download"]["url"] == original_url
    assert "buildDownloadOverrides" not in manifest
    assert events[0]["downloadUrl"] == original_url
    assert events[0]["effectiveDownloadUrl"] == local_url
    assert events[0]["source"] == "downloaded"


def test_build_direct_download_url_rewrite_first_match_wins(ws, direct_http_server):
    local_url, server, content = direct_http_server
    local_prefix = local_url.rsplit("/", 1)[0] + "/"
    original_url = "https://cdn.example.com/mod.jar"
    ws["buildDownloadOverrides"] = {
        "urlRewrites": [
            {"from": "https://cdn.example.com/", "to": local_prefix},
            {"from": "https://cdn.example.com/", "to": "http://127.0.0.1:1/"},
        ]
    }
    ws["mods"] = {
        "firstmatch": {
            "name": "First Match",
            "required": True,
            "license": "mit",
            "variants": [
                {
                    "selector": {},
                    "priority": 0,
                    "version": "1.0",
                    "localFile": None,
                    "fileName": "mod.jar",
                    "download": {"type": "direct", "url": original_url},
                }
            ],
        }
    }
    ws["minecraftVersion"] = "1.21.1"

    events = []
    manifest = build_manifest(ws, progress_callback=events.append)
    artifact = manifest["mods"]["firstmatch"]["variants"][0]["artifact"]

    assert artifact["size"] == len(content)
    assert artifact["download"]["url"] == original_url
    assert events[0]["effectiveDownloadUrl"] == local_url


def test_build_direct_download_file_url_rewrite_uses_file_without_temp(
    ws, tmp_path, monkeypatch
):
    jar_dir = tmp_path / "files"
    jar_dir.mkdir()
    jar_path = jar_dir / "mod.jar"
    content = b"hello-from-file-rewrite"
    jar_path.write_bytes(content)

    original_url = "https://cdn.example.com/mirror/mod.jar"
    ws["buildDownloadOverrides"] = {
        "urlRewrites": [
            {"from": "https://cdn.example.com/mirror/", "to": jar_dir.as_uri() + "/"},
        ]
    }
    ws["mods"] = {
        "filerewrite": {
            "name": "File Rewrite",
            "required": True,
            "license": "mit",
            "variants": [
                {
                    "selector": {},
                    "priority": 0,
                    "version": "1.0",
                    "localFile": None,
                    "fileName": "mod.jar",
                    "download": {"type": "direct", "url": original_url},
                }
            ],
        }
    }
    ws["minecraftVersion"] = "1.21.1"

    def fail_temp_file(*args, **kwargs):
        raise AssertionError("file:// rewrite must not create a temporary file")

    monkeypatch.setattr(tempfile, "NamedTemporaryFile", fail_temp_file)

    events = []
    manifest = build_manifest(ws, progress_callback=events.append)
    artifact = manifest["mods"]["filerewrite"]["variants"][0]["artifact"]

    assert artifact["size"] == len(content)
    assert artifact["hashes"]["sha256"] == hashlib.sha256(content).hexdigest()
    assert artifact["download"]["url"] == original_url
    assert events[0]["downloadUrl"] == original_url
    assert events[0]["effectiveDownloadUrl"] == jar_path.as_uri()
    assert events[0]["source"] == "local"


@pytest.mark.parametrize(
    ("overrides", "message"),
    [
        ([], "buildDownloadOverrides must be an object"),
        ({"urlRewrites": "bad"}, "buildDownloadOverrides.urlRewrites must be a list"),
        ({"urlRewrites": ["bad"]}, r"buildDownloadOverrides.urlRewrites\[0\] must be an object"),
        (
            {"urlRewrites": [{"from": "", "to": "http://127.0.0.1/"}]},
            r"buildDownloadOverrides.urlRewrites\[0\].from must be a non-empty string",
        ),
        (
            {"urlRewrites": [{"from": "https://cdn.example.com/", "to": ""}]},
            r"buildDownloadOverrides.urlRewrites\[0\].to must be a non-empty string",
        ),
        (
            {"urlRewrites": [{"from": "/mods/", "to": "http://127.0.0.1/"}]},
            r"buildDownloadOverrides.urlRewrites\[0\].from must be an absolute http\(s\) URL",
        ),
        (
            {"urlRewrites": [{"from": "https://cdn.example.com/", "to": "file://mirror/mods/"}]},
            r"buildDownloadOverrides.urlRewrites\[0\].to must be an absolute http\(s\) or local file:// URL",
        ),
        (
            {"urlRewrites": [{"from": "https://cdn.example.com/", "to": "file:mods/"}]},
            r"buildDownloadOverrides.urlRewrites\[0\].to must be an absolute http\(s\) or local file:// URL",
        ),
    ],
)
def test_build_download_overrides_validation(ws, overrides, message):
    ws["buildDownloadOverrides"] = overrides

    with pytest.raises(ValueError, match=message):
        build_manifest(ws)


def test_build_direct_download_failure(ws):
    """Downloading from a closed port must raise ValueError."""
    variant = {
        "selector": {},
        "priority": 0,
        "version": "1.0",
        "localFile": None,
        "fileName": "fail.jar",
        "download": {"type": "direct", "url": "http://127.0.0.1:19999/no-such.jar"},
    }
    ws["mods"] = {
        "faildirect": {
            "name": "Fail",
            "required": False,
            "license": "mit",
            "variants": [variant],
        }
    }
    ws["minecraftVersion"] = "1.21.1"

    with pytest.raises(
        ValueError, match="Download for mod 'faildirect' variant"
    ):
        build_manifest(ws)


def test_build_hosted_without_local_file_still_fails(ws):
    """Hosted downloads MUST still have a local file; direct path is unchanged."""
    ws["mods"] = {
        "nofile": {
            "name": "NoFile",
            "required": True,
            "license": "mit",
            "variants": [
                {
                    "selector": {},
                    "priority": 0,
                    "version": "1.0",
                    "localFile": None,
                    "fileName": "nope.jar",
                    "download": {"type": "hosted", "url": "https://example.com/nope.jar"},
                }
            ],
        }
    }
    ws["minecraftVersion"] = "1.21.1"

    with pytest.raises(ValueError, match="Missing local file for mod 'nofile'"):
        build_manifest(ws)


def test_direct_with_missing_local_file_raises(ws):
    """Direct download with a given localFile that does not exist must raise Missing local file ValueError."""
    variant = make_variant("/nonexistent/path.jar", download_type="direct")
    ws["mods"] = {
        "missingdirect": {
            "name": "MissingDirect",
            "required": True,
            "license": "mit",
            "variants": [variant],
        }
    }
    with pytest.raises(ValueError, match="Missing local file for mod 'missingdirect'"):
        build_manifest(ws)


def test_build_delete_action(ws):
    ws["mods"] = {
        "todelete": {
            "name": "DelMod",
            "required": False,
            "action": "delete",
            "variants": [],
        }
    }
    ws["minecraftVersion"] = "1.21.1"
    manifest = build_manifest(ws)
    mod = manifest["mods"]["todelete"]
    assert mod["name"] == "DelMod"
    assert mod["required"] is False
    assert mod["action"] == "delete"
    assert mod["variants"] == []


def test_build_no_progress_for_delete(ws):
    events = []
    def progress(ev):
        events.append(ev)
    import os, tempfile
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as tf:
        tf.write(b"abc")
        tf.flush()
        ws["mods"] = {
            "deleteme": {
                "name": "del",
                "required": False,
                "action": "delete",
                "variants": [],
            },
            "realmod": {
                "name": "real",
                "required": True,
                "license": "mit",
                "variants": [
                    {
                        "selector": {},
                        "priority": 0,
                        "version": "1.0",
                        "fileName": "real.jar",
                        "localFile": tf.name,
                        "download": {"type": "hosted", "url": "https://example.com/real.jar"},
                    }
                ],
            },
        }
        ws["minecraftVersion"] = "1.21.1"
        manifest = build_manifest(ws, progress_callback=progress)
        # delete mod should NOT invoke progress callback
        assert len(events) == 1
        assert events[0]["modid"] == "realmod"
        assert manifest["mods"]["deleteme"]["action"] == "delete"
    os.unlink(tf.name)


# ---- Delete variant tests ----

def test_build_delete_variant_outputs_action_and_no_artifact(ws):
    ws["minecraftVersion"] = "1.21.1"
    sel = {"loaders": ["fabric"]}
    ws["mods"] = {
        "todel": {
            "name": "ToDelete",
            "required": False,
            "variants": [
                {
                    "selector": sel,
                    "priority": 5,
                    "action": "delete",
                }
            ],
        }
    }
    manifest = build_manifest(ws)
    mod = manifest["mods"]["todel"]
    assert mod["name"] == "ToDelete"
    assert mod["required"] is False
    assert len(mod["variants"]) == 1
    var = mod["variants"][0]
    assert var["selector"] == sel
    assert var["priority"] == 5
    assert var["action"] == "delete"
    assert "artifact" not in var


def test_build_delete_variant_does_not_fire_progress(ws):
    events = []
    def progress(ev):
        events.append(ev)
    ws["minecraftVersion"] = "1.21.1"
    ws["mods"] = {
        "silent": {
            "name": "SilentDelete",
            "required": True,
            "variants": [
                {
                    "selector": {},
                    "priority": 0,
                    "action": "delete",
                }
            ],
        }
    }
    manifest = build_manifest(ws, progress_callback=progress)
    assert len(events) == 0
    assert manifest["mods"]["silent"]["variants"][0]["action"] == "delete"
