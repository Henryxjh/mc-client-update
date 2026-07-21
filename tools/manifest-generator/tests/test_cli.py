import json
import zipfile
import os
import subprocess
import sys
from pathlib import Path

import pytest


@pytest.fixture
def runner(tmp_path):
    """Fixture that invokes the CLI module with PYTHONPATH pointing to src."""

    src_dir = str(Path(__file__).resolve().parent.parent / "src")

    def _run(*args):
        env = os.environ.copy()
        py_path = env.get("PYTHONPATH", "")
        env["PYTHONPATH"] = os.pathsep.join(p for p in [src_dir, py_path] if p)
        return subprocess.run(
            [sys.executable, "-m", "mcumanifest"] + list(args),
            capture_output=True,
            text=True,
            env=env,
        )

    return _run


def test_help(runner):
    result = runner("--help")
    assert result.returncode == 0
    assert "usage:" in result.stdout


def test_init_creates_workspace(tmp_path, runner):
    ws_file = tmp_path / "manifest-workspace.json"
    result = runner(
        "--workspace",
        str(ws_file),
        "init",
        "--manifest-id",
        "test",
        "--mc",
        "1.20.1",
        "--force",
    )
    assert result.returncode == 0
    assert ws_file.exists()


def test_fish_completion_contains_loongarch64(runner):
    result = runner("completion", "fish")
    assert result.returncode == 0
    # fish completion must output a “complete” command
    assert "complete" in result.stdout
    # arch enumeration includes loongarch64
    assert "loongarch64" in result.stdout
    # assert descriptions for minimal required options
    assert '-d "Mod loader"' in result.stdout
    assert '-d "CPU architecture"' in result.stdout
    assert '-d "Provider name"' in result.stdout
    assert '-d "Project ID"' in result.stdout
    assert '-d "Base URL for hosted mods"' in result.stdout
    assert '-d "Fail if variant already exists"' in result.stdout

    # subcommand descriptions in completion
    assert "Create a new manifest workspace" in result.stdout
    assert "Add a direct-download artifact" in result.stdout
    assert "Remove a mod or variant" in result.stdout
    assert "Generate shell completion script" in result.stdout


def test_direct_rejects_relative_url(tmp_path, runner):
    ws_file = tmp_path / "test-ws.json"
    # initialise workspace first so that add-direct can load it
    runner(
        "--workspace",
        str(ws_file),
        "init",
        "--manifest-id",
        "rel-test",
        "--mc",
        "1.20.1",
        "--force",
    )
    result = runner(
        "--workspace",
        str(ws_file),
        "add-direct",
        "sodium",
        "--file",
        "/dev/null",
        "--version",
        "1.0",
        "--url",
        "mods/sodium.jar",
    )
    assert result.returncode != 0
    assert "http://" in result.stderr.lower() or "https://" in result.stderr.lower()


def test_fish_completion_contains_set_version_policy(runner):
    result = runner("completion", "fish")
    assert result.returncode == 0
    assert "set-version-policy" in result.stdout
    assert "-d \"Set skip-if-installed-version-greater-than policy for a mod\"" in result.stdout
    assert "-l skip-if-installed-version-greater-than" in result.stdout
    assert "-l clear-skip-if-installed-version-greater-than" in result.stdout
    assert "-d \"Version threshold for skipping install\"" in result.stdout


def test_set_version_policy_and_clear(tmp_path, runner):
    import json
    ws_file = tmp_path / "ws.json"
    # init workspace
    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")
    # manually add a mod entry
    ws_data = json.loads(ws_file.read_text())
    ws_data.setdefault("mods", {})["testmod"] = {
        "name": "Test Mod", "required": True, "variants": []
    }
    ws_file.write_text(json.dumps(ws_data))
    # set skip
    res = runner("--workspace", str(ws_file), "set-version-policy",
                 "testmod", "--skip-if-installed-version-greater-than", "1.2.3")
    assert res.returncode == 0
    ws2 = json.loads(ws_file.read_text())
    assert ws2["mods"]["testmod"]["skipIfInstalledVersionGreaterThan"] == "1.2.3"
    # clear
    res2 = runner("--workspace", str(ws_file), "set-version-policy",
                  "testmod", "--clear-skip-if-installed-version-greater-than")
    assert res2.returncode == 0
    ws3 = json.loads(ws_file.read_text())
    assert "skipIfInstalledVersionGreaterThan" not in ws3["mods"]["testmod"]


def test_set_version_policy_mod_not_found(tmp_path, runner):
    ws_file = tmp_path / "ws.json"
    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")
    res = runner("--workspace", str(ws_file), "set-version-policy",
                 "nope", "--skip-if-installed-version-greater-than", "1.0")
    assert res.returncode != 0
    assert "not found" in res.stderr.lower()


def test_set_version_policy_rejects_conflicting_flags(tmp_path, runner):
    ws_file = tmp_path / "ws.json"
    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")
    ws_data = json.loads(ws_file.read_text())
    ws_data.setdefault("mods", {})["testmod"] = {
        "name": "Test Mod", "required": True, "variants": []
    }
    ws_file.write_text(json.dumps(ws_data))
    res = runner("--workspace", str(ws_file), "set-version-policy",
                 "testmod",
                 "--skip-if-installed-version-greater-than", "1.0",
                 "--clear-skip-if-installed-version-greater-than")
    assert res.returncode != 0
    assert "exactly one" in res.stderr.lower()


def test_set_version_policy_rejects_blank_version(tmp_path, runner):
    ws_file = tmp_path / "ws.json"
    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")
    ws_data = json.loads(ws_file.read_text())
    ws_data.setdefault("mods", {})["testmod"] = {
        "name": "Test Mod", "required": True, "variants": []
    }
    ws_file.write_text(json.dumps(ws_data))
    res = runner("--workspace", str(ws_file), "set-version-policy",
                 "testmod", "--skip-if-installed-version-greater-than", " ")
    assert res.returncode != 0
    assert "must not be blank" in res.stderr.lower()


# ---------------------------------------------------------------------------
# Tests for add-direct without --file
# ---------------------------------------------------------------------------

def test_add_direct_no_file_writes_workspace(tmp_path, runner):
    ws_file = tmp_path / "ws.json"
    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")

    result = runner("--workspace", str(ws_file), "add-direct", "sodium",
                    "--version", "1.0", "--url", "https://example.com/path/to/mod.jar")
    assert result.returncode == 0

    ws_data = json.loads(ws_file.read_text())
    mod = ws_data["mods"]["sodium"]
    assert mod["name"] == "sodium"
    assert len(mod["variants"]) == 1
    var = mod["variants"][0]
    assert var["localFile"] is None
    # fileName is inferred from URL basename
    assert var["fileName"] == "mod.jar"
    download = var["download"]
    assert download["type"] == "direct"
    assert download["url"] == "https://example.com/path/to/mod.jar"


def test_add_direct_infers_file_name_when_url_has_no_basename(tmp_path, runner):
    ws_file = tmp_path / "ws.json"
    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")

    # URL path is just '/', so no basename
    result = runner("--workspace", str(ws_file), "add-direct", "noext",
                    "--version", "2.0", "--url", "https://cdn.example.com/")
    assert result.returncode == 0

    ws_data = json.loads(ws_file.read_text())
    var = ws_data["mods"]["noext"]["variants"][0]
    assert var["localFile"] is None
    # fallback: <modid>.jar
    assert var["fileName"] == "noext.jar"
    assert var["download"]["url"] == "https://cdn.example.com/"


def test_build_progress_output(tmp_path, runner):
    ws_file = tmp_path / "ws.json"
    jar_file = tmp_path / "testmod.jar"
    with zipfile.ZipFile(jar_file, "w") as zf:
        zf.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        zf.writestr("Dummy.class", b'\x00')

    schema_file = tmp_path / "schema.json"
    schema_data = {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "object",
        "additionalProperties": True
    }
    schema_file.write_text(json.dumps(schema_data))

    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")
    res = runner("--workspace", str(ws_file), "add-hosted", "testmod",
                 "--file", str(jar_file), "--url", "mods/testmod.jar",
                 "--version", "1.0.0", "--loader", "fabric")
    assert res.returncode == 0

    out_manifest = tmp_path / "manifest.json"
    result = runner("--workspace", str(ws_file), "build",
                    "--base-url", "https://example.com/",
                    "--output", str(out_manifest),
                    "--schema", str(schema_file))
    assert result.returncode == 0
    stdout = result.stdout
    assert "Building manifest" in stdout
    assert "OK artifact" in stdout
    assert "modid=testmod" in stdout
    assert "variantIndex=0" in stdout
    assert "fileName=testmod.jar" in stdout
    assert "version=1.0.0" in stdout
    assert "downloadType=hosted" in stdout
    assert "size=" in stdout
    assert "source=local" in stdout
    assert "Schema validation passed" in stdout
    assert "Manifest written" in stdout
    assert out_manifest.is_file()


def test_build_no_progress(tmp_path, runner):
    ws_file = tmp_path / "ws.json"
    jar_file = tmp_path / "testmod.jar"
    with zipfile.ZipFile(jar_file, "w") as zf:
        zf.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        zf.writestr("Dummy.class", b'\x00')

    schema_file = tmp_path / "schema.json"
    schema_data = {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "object",
        "additionalProperties": True
    }
    schema_file.write_text(json.dumps(schema_data))

    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")
    res = runner("--workspace", str(ws_file), "add-hosted", "testmod",
                 "--file", str(jar_file), "--url", "mods/testmod.jar",
                 "--version", "1.0.0", "--loader", "fabric")
    assert res.returncode == 0

    out_manifest = tmp_path / "manifest.json"
    result = runner("--workspace", str(ws_file), "build",
                    "--base-url", "https://example.com/",
                    "--output", str(out_manifest),
                    "--schema", str(schema_file),
                    "--no-progress")
    assert result.returncode == 0
    stdout = result.stdout
    assert "Building manifest" not in stdout
    assert "OK" not in stdout
    assert "Schema validation passed" not in stdout
    assert "Manifest written" in stdout
    assert out_manifest.is_file()


def test_add_delete_writes_workspace(tmp_path, runner):
    ws_file = tmp_path / "ws.json"
    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")
    res = runner("--workspace", str(ws_file), "add-delete", "mymod")
    assert res.returncode == 0
    data = json.loads(ws_file.read_text())
    assert "mymod" in data["mods"]
    mod = data["mods"]["mymod"]
    assert mod["name"] == "mymod"
    assert mod["required"] is False
    assert mod["action"] == "delete"
    assert mod["variants"] == []


def test_add_delete_existing_fails_without_force(tmp_path, runner):
    ws_file = tmp_path / "ws.json"
    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")
    runner("--workspace", str(ws_file), "add-delete", "mymod")
    res = runner("--workspace", str(ws_file), "add-delete", "mymod")
    assert res.returncode != 0
    assert "already exists" in res.stderr.lower() or "aborted" in res.stderr.lower()


def test_add_delete_existing_with_force(tmp_path, runner):
    ws_file = tmp_path / "ws.json"
    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")
    runner("--workspace", str(ws_file), "add-delete", "mymod")
    res = runner("--workspace", str(ws_file), "add-delete", "--force", "mymod")
    assert res.returncode == 0
    data = json.loads(ws_file.read_text())
    assert data["mods"]["mymod"]["action"] == "delete"


def test_add_delete_no_overwrite_on_existing(tmp_path, runner):
    ws_file = tmp_path / "ws.json"
    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")
    runner("--workspace", str(ws_file), "add-delete", "mymod")
    res = runner("--workspace", str(ws_file), "add-delete", "--no-overwrite", "mymod")
    assert res.returncode != 0
    assert "no-overwrite" in res.stderr.lower() or "error" in res.stderr.lower()


def test_build_skip_delete_in_progress(tmp_path, runner):
    ws_file = tmp_path / "ws.json"
    jar_file = tmp_path / "mod.jar"
    with zipfile.ZipFile(jar_file, "w") as zf:
        zf.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        zf.writestr("Dummy.class", b'\x00')

    schema_file = tmp_path / "schema.json"
    schema_data = {"$schema": "http://json-schema.org/draft-07/schema#", "type": "object", "additionalProperties": True}
    schema_file.write_text(json.dumps(schema_data))

    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")
    # add a hosted variant so that there is at least one normal artifact
    runner("--workspace", str(ws_file), "add-hosted", "hostedmod",
           "--file", str(jar_file), "--url", "mods/hostedmod.jar",
           "--version", "1.0", "--loader", "fabric")
    # add a delete action
    runner("--workspace", str(ws_file), "add-delete", "deletemod")

    out_manifest = tmp_path / "manifest.json"
    result = runner("--workspace", str(ws_file), "build",
                    "--base-url", "https://example.com/",
                    "--output", str(out_manifest),
                    "--schema", str(schema_file))
    assert result.returncode == 0
    stdout = result.stdout
    # Progress output should list the hosted artifact, but NOT the delete mod
    assert "OK artifact: modid=hostedmod" in stdout
    assert "deletemod" not in stdout

    manifest_data = json.loads(out_manifest.read_text())
    assert "deletemod" in manifest_data["mods"]
    assert manifest_data["mods"]["deletemod"]["action"] == "delete"
    assert manifest_data["mods"]["deletemod"]["variants"] == []


def test_add_delete_variant_level_writes_workspace(tmp_path, runner):
    import json
    ws_file = tmp_path / "ws.json"
    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")
    res = runner("--workspace", str(ws_file), "add-delete", "modv",
                 "--loader", "fabric", "--os", "linux", "--arch", "x86_64")
    assert res.returncode == 0
    data = json.loads(ws_file.read_text())
    assert "modv" in data["mods"]
    mod = data["mods"]["modv"]
    assert mod["required"] is False
    assert len(mod["variants"]) == 1
    var = mod["variants"][0]
    assert var["selector"] == {"loaders": ["fabric"], "operatingSystems": ["linux"], "architectures": ["x86_64"]}
    assert var["priority"] == 0
    assert var["action"] == "delete"
    assert "artifact" not in var


# ---------------------------------------------------------------------------
# Tests for list -f and list -q
# ---------------------------------------------------------------------------

def test_list_quiet_modids(tmp_path, runner):
    ws_file = tmp_path / "ws.json"
    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")
    # Add a few mods
    for mid in ("sodium", "lithium", "iris"):
        data = json.loads(ws_file.read_text())
        data.setdefault("mods", {})[mid] = {"name": mid, "required": True, "variants": []}
        ws_file.write_text(json.dumps(data))

    result = runner("--workspace", str(ws_file), "list", "-q")
    assert result.returncode == 0
    lines = result.stdout.strip().splitlines()
    assert lines == ["iris", "lithium", "sodium"]


def test_list_quiet_files(tmp_path, runner):
    ws_file = tmp_path / "ws.json"
    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")
    data = json.loads(ws_file.read_text())
    data.setdefault("mods", {})["moda"] = {
        "name": "ModA", "required": True,
        "variants": [
            {"localFile": "/a/b.jar", "version": "1"},
            {"fileName": "c.jar", "version": "2"},
            {"version": "3"},
        ]
    }
    data["mods"]["modb"] = {"name": "ModB", "required": True, "variants": []}
    ws_file.write_text(json.dumps(data))

    result = runner("--workspace", str(ws_file), "list", "-q", "-f")
    assert result.returncode == 0
    lines = result.stdout.strip().splitlines()
    # modb has no variants → no file output
    # moda: localFile → /a/b.jar; fileName → c.jar; none → -
    assert lines == ["/a/b.jar", "c.jar", "-"]


def test_list_file_flag_has_column(tmp_path, runner):
    ws_file = tmp_path / "ws.json"
    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")
    data = json.loads(ws_file.read_text())
    data.setdefault("mods", {})["testmod"] = {
        "name": "TestMod", "required": True,
        "variants": [{"localFile": "/x/y.jar", "version": "1.0"}]
    }
    ws_file.write_text(json.dumps(data))

    result = runner("--workspace", str(ws_file), "list", "-f")
    assert result.returncode == 0
    assert "file" in result.stdout.lower() or "/x/y.jar" in result.stdout
    assert "/x/y.jar" in result.stdout


def test_set_version_policy_now(tmp_path, runner):
    ws_file = tmp_path / "ws.json"
    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")
    data = json.loads(ws_file.read_text())
    data.setdefault("mods", {})["testmod"] = {
        "name": "Test Mod", "required": True,
        "variants": [
            {"version": "2.5.0", "download": {"type": "hosted", "url": "x"}},
        ]
    }
    ws_file.write_text(json.dumps(data))

    res = runner("--workspace", str(ws_file), "set-version-policy",
                 "testmod", "--skip-if-installed-version-greater-than-now")
    assert res.returncode == 0
    ws2 = json.loads(ws_file.read_text())
    assert ws2["mods"]["testmod"]["skipIfInstalledVersionGreaterThan"] == "2.5.0"


def test_set_version_policy_now_no_variants(tmp_path, runner):
    ws_file = tmp_path / "ws.json"
    runner("--workspace", str(ws_file), "init",
           "--manifest-id", "test", "--mc", "1.20.1", "--force")
    data = json.loads(ws_file.read_text())
    data.setdefault("mods", {})["testmod"] = {
        "name": "Test Mod", "required": True, "variants": []
    }
    ws_file.write_text(json.dumps(data))

    res = runner("--workspace", str(ws_file), "set-version-policy",
                 "testmod", "--skip-if-installed-version-greater-than-now")
    assert res.returncode != 0
    assert "no variants" in res.stderr.lower()


def test_fish_completion_contains_list_flags(runner):
    result = runner("completion", "fish")
    assert result.returncode == 0
    assert "-s f -l file -d \"Show local file paths\"" in result.stdout
    assert "-s q -l quiet" in result.stdout
