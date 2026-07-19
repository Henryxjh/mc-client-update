import json
from typing import Any, Dict


_SCHEMA_PATH = "docs/client-update-manifest.schema.json"


def _validate_with_jsonschema(manifest: Dict[str, Any], schema: Dict[str, Any]) -> None:
    import jsonschema

    jsonschema.validate(instance=manifest, schema=schema)


def _validate_semantic(manifest: Dict[str, Any]) -> None:
    """Basic structural checks that mirror the most important schema requirements."""
    if not isinstance(manifest, dict):
        raise ValueError("Manifest must be a JSON object")

    required_top = [
        "schemaVersion",
        "manifestId",
        "revision",
        "generatedAt",
        "minecraftVersion",
        "mods",
    ]
    for key in required_top:
        if key not in manifest:
            raise ValueError(f"Missing required field: {key}")

    if manifest["schemaVersion"] != 1:
        raise ValueError("schemaVersion must be 1")

    mods = manifest["mods"]
    if not isinstance(mods, dict) or len(mods) == 0:
        raise ValueError("'mods' must be a non‑empty object")

    for modid, mod_data in mods.items():
        if not isinstance(mod_data, dict):
            raise ValueError(f"mod '{modid}' must be an object")
        if "name" not in mod_data:
            raise ValueError(f"mod '{modid}' missing required field 'name'")
        required = mod_data.get("required")
        if required is None or not isinstance(required, bool):
            raise ValueError(f"mod '{modid}' missing or invalid 'required'")
        variants = mod_data.get("variants")
        if not isinstance(variants, list):
            raise ValueError(f"mod '{modid}' must have a 'variants' list")
        for i, var in enumerate(variants):
            if not isinstance(var, dict):
                raise ValueError(
                    f"mod '{modid}' variant {i} must be an object"
                )
            if "selector" not in var:
                raise ValueError(
                    f"mod '{modid}' variant {i} missing 'selector'"
                )
            # Delete variants may omit artifact completely
            if var.get("action") == "delete":
                continue
            artifact = var.get("artifact")
            if not isinstance(artifact, dict):
                raise ValueError(
                    f"mod '{modid}' variant {i} missing or invalid 'artifact'"
                )


def validate_manifest(
    manifest: Dict[str, Any], schema_path: str = _SCHEMA_PATH
) -> None:
    """Validate *manifest* against the JSON Schema at *schema_path*.

    If the ``jsonschema`` package is available it is used for full
    validation; otherwise a lightweight semantic fallback is performed.

    Missing schema file also triggers the semantic fallback.

    All validation errors are raised as :class:`ValueError` with a
    human-readable message.
    """
    # Always run semantic checks first for stable error messages.
    _validate_semantic(manifest)

    # Try to import jsonschema; if unavailable semantic check is enough.
    try:
        import jsonschema as _js  # noqa: F401
    except ImportError:
        return

    # Load schema file if present; otherwise skip full validation.
    try:
        with open(schema_path, encoding="utf-8") as fh:
            schema = json.load(fh)
    except (FileNotFoundError, OSError):
        return

    # Perform full JSON Schema validation and convert any
    # jsonschema exceptions into ValueError with the same message.
    try:
        _validate_with_jsonschema(manifest, schema)
    except _js.ValidationError as exc:
        raise ValueError(str(exc)) from exc
    except _js.SchemaError as exc:
        raise ValueError(str(exc)) from exc
