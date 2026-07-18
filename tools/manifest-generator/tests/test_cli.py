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
