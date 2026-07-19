import json
import os
import tempfile

import pytest

from mcumanifest.validator import validate_manifest


def make_valid_manifest():
    return {
        "schemaVersion": 1,
        "manifestId": "test",
        "revision": 1,
        "generatedAt": "2026-07-17T12:00:00Z",
        "minecraftVersion": "1.21.1",
        "mods": {
            "moda": {
                "name": "ModA",
                "required": False,
                "variants": [
                    {
                        "selector": {"loaders": ["fabric"]},
                        "priority": 0,
                        "artifact": {
                            "version": "1.0",
                            "fileName": "mod.jar",
                            "size": 123,
                            "hashes": {
                                "sha256": 64 * "a",
                                "sha512": 128 * "b",
                            },
                            "download": {
                                "type": "hosted",
                                "url": "https://example.com/mod.jar",
                            },
                        },
                    }
                ],
            }
        },
    }


def test_valid_manifest_passes_semantic():
    manifest = make_valid_manifest()
    validate_manifest(manifest)  # no exception


def test_missing_required_field_fails_semantic():
    manifest = make_valid_manifest()
    del manifest["mods"]
    with pytest.raises(ValueError, match="Missing required field"):
        validate_manifest(manifest)


def test_missing_not_a_dict_fails():
    with pytest.raises(ValueError, match="must be a JSON object"):
        validate_manifest(["not", "an", "object"])


def test_mod_missing_name_fails():
    manifest = make_valid_manifest()
    del manifest["mods"]["moda"]["name"]
    with pytest.raises(ValueError, match="missing required field 'name'"):
        validate_manifest(manifest)


def test_mod_invalid_required_fails():
    manifest = make_valid_manifest()
    manifest["mods"]["moda"]["required"] = "yes"
    with pytest.raises(ValueError, match="missing or invalid 'required'"):
        validate_manifest(manifest)


def test_variant_missing_selector_fails():
    manifest = make_valid_manifest()
    del manifest["mods"]["moda"]["variants"][0]["selector"]
    with pytest.raises(ValueError, match="missing 'selector'"):
        validate_manifest(manifest)


def test_delete_variant_without_artifact_passes():
    manifest = make_valid_manifest()
    manifest["mods"]["moda"]["variants"] = [
        {
            "selector": {"loaders": ["fabric"]},
            "priority": 0,
            "action": "delete",
        }
    ]
    validate_manifest(manifest)


def test_install_variant_without_artifact_fails():
    manifest = make_valid_manifest()
    del manifest["mods"]["moda"]["variants"][0]["artifact"]
    with pytest.raises(ValueError, match="artifact"):
        validate_manifest(manifest)
