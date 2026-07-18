import datetime
import os
from typing import Any, Dict

from mcumanifest.hashing import file_size, hash_file_many
from mcumanifest.licenses import hosted_allowed
from mcumanifest.workspace import normalize_selector, selector_equal


def add_or_update_variant(
    workspace: Dict[str, Any],
    modid: str,
    selector: Dict[str, Any],
    variant: Dict[str, Any],
    force: bool = False,
    no_overwrite: bool = False,
) -> str:
    """Add or update a variant in *workspace* for the given *modid*.

    Returns one of ``"added"``, ``"appended"``, ``"updated"`` or ``"conflict"``.
    """
    mods = workspace.setdefault("mods", {})
    existed = modid in mods

    if not existed:
        mod_entry: Dict[str, Any] = {"name": modid, "required": True, "variants": []}
        mods[modid] = mod_entry
    else:
        mod_entry = mods[modid]

    norm = normalize_selector(selector)
    existing_idx = None
    for idx, existing in enumerate(mod_entry.get("variants", [])):
        if selector_equal(existing.get("selector", {}), selector):
            existing_idx = idx
            break

    if existing_idx is not None:
        if no_overwrite:
            return "conflict"
        if force:
            mod_entry["variants"][existing_idx] = variant
            return "updated"
        return "conflict"

    # No matching selector – append to the variant list.
    variant_list = mod_entry.setdefault("variants", [])
    variant_list.append(variant)

    if not existed:
        return "added"
    return "appended"


def build_manifest(
    workspace: Dict[str, Any], allow_redistribution: bool = False
) -> Dict[str, Any]:
    """Build a final manifest dictionary from *workspace*.

    The returned dict strictly follows the schema described in
    ``docs/client-update-manifest.schema.json``.
    """
    manifest: Dict[str, Any] = {
        "schemaVersion": 1,
        "manifestId": workspace.get("manifestId", "generated"),
        "revision": workspace.get("revision", 1),
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "minecraftVersion": workspace.get("minecraftVersion", "1.21.1"),
        "mods": {},
    }

    if "expiresAt" in workspace:
        manifest["expiresAt"] = workspace["expiresAt"]
    if "minimumLoaderVersions" in workspace:
        manifest["minimumLoaderVersions"] = workspace["minimumLoaderVersions"]
    base_url = workspace.get("baseUrl")
    if base_url:
        manifest["baseUrl"] = base_url

    mods_src = workspace.get("mods", {})
    for modid, mod_data in mods_src.items():
        mod_entry: Dict[str, Any] = {
            "name": mod_data.get("name", modid),
            "required": mod_data.get("required", False),
        }
        if "homepage" in mod_data:
            mod_entry["homepage"] = mod_data["homepage"]
        license_val = mod_data.get("license")
        if license_val is not None:
            mod_entry["license"] = license_val

        built_variants = []
        for idx, var_data in enumerate(mod_data.get("variants", [])):
            # ---------- local file (required for hash computation) ----------
            local_file = var_data.get("localFile")
            if not local_file or not os.path.isfile(local_file):
                raise ValueError(
                    f"Missing local file for mod '{modid}' variant {idx}: {local_file}"
                )
            # ---------- hashes ----------
            hashes = hash_file_many(local_file, ["sha256", "sha512"])
            size_val = file_size(local_file)

            # ---------- download information ----------
            download_raw = var_data.get("download")
            if not download_raw:
                raise ValueError(
                    f"No download information for mod '{modid}' variant {idx}"
                )
            dl_type = download_raw.get("type")
            if dl_type == "hosted":
                mod_allow = bool(mod_data.get("allowRedistribution", allow_redistribution))
                if not hosted_allowed(license_val, mod_allow):
                    raise ValueError(
                        f"License for mod '{modid}' does not allow hosted redistribution"
                    )

            artifact: Dict[str, Any] = {
                "version": var_data.get("version", "0.0.0"),
                "fileName": var_data.get(
                    "fileName", os.path.basename(local_file)
                ),
                "size": size_val,
                "hashes": {
                    "sha256": hashes["sha256"],
                    "sha512": hashes["sha512"],
                },
                "download": download_raw,
            }

            variant_entry: Dict[str, Any] = {
                "selector": var_data.get("selector"),
                "priority": var_data.get("priority", 0),
                "artifact": artifact,
            }
            built_variants.append(variant_entry)

        mod_entry["variants"] = built_variants
        manifest["mods"][modid] = mod_entry

    return manifest
