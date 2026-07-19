import datetime
import os
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, Optional, Callable

from mcumanifest.hashing import file_size, hash_file_many
from mcumanifest.licenses import hosted_allowed
from mcumanifest.workspace import normalize_selector, selector_equal

_USER_AGENT = "mcumanifest/1.0"


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
    workspace: Dict[str, Any],
    allow_redistribution: bool = False,
    progress_callback: Optional[Callable[[dict], None]] = None,
) -> Dict[str, Any]:
    """Build a final manifest dictionary from *workspace*.

    If *progress_callback* is not ``None``, it will be called for every
    successfully built artifact with an event dict holding at least the
    following keys: ``event`` (``"artifact_built"``), ``modid``,
    ``variantIndex``, ``fileName``, ``version``, ``downloadType``,
    ``size``, and ``source`` (``"downloaded"`` or ``"local"``).

    The returned dictionary strictly follows the schema described in
    ``docs/client-update-manifest.schema.json``.
    """
    manifest: Dict[str, Any] = {
        "schemaVersion": 1,
        "manifestId": workspace.get("manifestId", "generated"),
        "revision": workspace.get("revision", 1),
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%SZ"
        ),
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
        if mod_data.get("action") == "delete":
            manifest["mods"][modid] = {
                "name": mod_data.get("name", modid),
                "required": mod_data.get("required", False),
                "action": "delete",
                "variants": [],
            }
            continue
        mod_entry: Dict[str, Any] = {
            "name": mod_data.get("name", modid),
            "required": mod_data.get("required", False),
        }
        if "homepage" in mod_data:
            mod_entry["homepage"] = mod_data["homepage"]
        license_val = mod_data.get("license")
        if license_val is not None:
            mod_entry["license"] = license_val

        # skipIfInstalledVersionGreaterThan
        skip_ver_raw = mod_data.get("skipIfInstalledVersionGreaterThan")
        if skip_ver_raw is not None:
            if isinstance(skip_ver_raw, str):
                skip_ver = skip_ver_raw.strip()
            else:
                skip_ver = str(skip_ver_raw).strip()
            if skip_ver == "":
                raise ValueError(
                    f"skipIfInstalledVersionGreaterThan for mod '{modid}' must not be blank"
                )
            mod_entry["skipIfInstalledVersionGreaterThan"] = skip_ver

        built_variants = []
        for idx, var_data in enumerate(mod_data.get("variants", [])):
            # ---------- download information ----------
            download_raw = var_data.get("download")
            if not download_raw:
                raise ValueError(
                    f"No download information for mod '{modid}' variant {idx}"
                )
            dl_type = download_raw.get("type")

            # ---------- resolve the file to compute hashes ----------
            local_file_raw = var_data.get("localFile")
            # Normalize to a non-empty string, or None
            explicit_local = (
                str(local_file_raw).strip()
                if local_file_raw is not None and str(local_file_raw).strip() != ""
                else None
            )
            compute_path = None
            use_temp = False

            if dl_type in ("hosted", "manual"):
                # Hosted / manual *must* have a local file that exists
                if not explicit_local or not os.path.isfile(explicit_local):
                    raise ValueError(
                        f"Missing local file for mod '{modid}' variant {idx}"
                    )
                compute_path = explicit_local
            elif dl_type == "direct":
                if explicit_local is not None:
                    # User provided an explicit local file; if it exists use it,
                    # otherwise raise (never fall back to downloading).
                    if not os.path.isfile(explicit_local):
                        raise ValueError(
                            f"Missing local file for mod '{modid}' variant {idx}"
                        )
                    compute_path = explicit_local
                else:
                    # No local file – download to a temporary file
                    url = download_raw.get("url")
                    if not url:
                        raise ValueError(
                            f"Direct download URL for mod '{modid}' variant {idx} is missing"
                        )
                    # Validate that the URL is an absolute http(s) URL
                    parsed = urllib.parse.urlparse(url)
                    if parsed.scheme not in ("http", "https") or not parsed.netloc:
                        raise ValueError(
                            f"Direct download URL for mod '{modid}' variant {idx} "
                            "must be an absolute http or https URL"
                        )

                    tmp_file = tempfile.NamedTemporaryFile(
                        delete=False, suffix=".jar"
                    )
                    tmp_path = tmp_file.name
                    try:
                        req = urllib.request.Request(
                            url, headers={"User-Agent": _USER_AGENT}
                        )
                        with urllib.request.urlopen(req) as response:
                            if response.status != 200:
                                raise urllib.error.HTTPError(
                                    url,
                                    response.status,
                                    "Not OK",
                                    response.headers,
                                    None,
                                )
                            # Chunked read – avoid reading the whole file into memory
                            while True:
                                chunk = response.read(8192)
                                if not chunk:
                                    break
                                tmp_file.write(chunk)
                            tmp_file.flush()
                    except urllib.error.HTTPError as e:
                        # Clean up the temporary file
                        try:
                            tmp_file.close()
                        except OSError:
                            pass
                        try:
                            os.unlink(tmp_path)
                        except OSError:
                            pass
                        raise ValueError(
                            f"Download for mod '{modid}' variant {idx} returned HTTP {e.code}"
                            " (expected 200)"
                        ) from e
                    except Exception as e:
                        try:
                            tmp_file.close()
                        except OSError:
                            pass
                        try:
                            os.unlink(tmp_path)
                        except OSError:
                            pass
                        raise ValueError(
                            f"Download for mod '{modid}' variant {idx} failed: {e}"
                        ) from e
                    else:
                        tmp_file.close()
                        compute_path = tmp_path
                        use_temp = True
            else:
                raise ValueError(
                    f"Unsupported download type '{dl_type}' for mod '{modid}' variant {idx}"
                )

            # ---------- compute hashes and size ----------
            try:
                hashes = hash_file_many(compute_path, ["sha256", "sha512"])
                size_val = file_size(compute_path)
            finally:
                if use_temp:
                    try:
                        os.unlink(compute_path)
                    except OSError:
                        pass

            # ---------- infer fileName if missing ----------
            fileName = var_data.get("fileName")
            if not fileName:
                if dl_type == "direct":
                    url = download_raw.get("url")
                    if url:
                        parsed_path = (
                            urllib.parse.urlparse(url).path
                        )
                        base = os.path.basename(parsed_path.rstrip("/") or "")
                        if base and base != "/":
                            fileName = base
                        else:
                            fileName = f"{modid}.jar"
                    else:
                        fileName = f"{modid}.jar"
                else:
                    fileName = f"{modid}.jar"

            # ---------- license check for hosted ----------
            if dl_type == "hosted":
                mod_allow = bool(
                    mod_data.get("allowRedistribution", allow_redistribution)
                )
                if not hosted_allowed(license_val, mod_allow):
                    raise ValueError(
                        f"License for mod '{modid}' does not allow hosted redistribution"
                    )

            artifact: Dict[str, Any] = {
                "version": var_data.get("version", "0.0.0"),
                "fileName": fileName,
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

            if progress_callback is not None:
                if dl_type == "direct" and not explicit_local and use_temp:
                    source = "downloaded"
                else:
                    source = "local"
                event_dict = {
                    "event": "artifact_built",
                    "modid": modid,
                    "variantIndex": idx,
                    "fileName": fileName,
                    "version": artifact["version"],
                    "downloadType": dl_type,
                    "size": size_val,
                    "source": source,
                }
                progress_callback(event_dict)

        mod_entry["variants"] = built_variants
        manifest["mods"][modid] = mod_entry

    return manifest
