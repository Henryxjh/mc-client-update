import argparse
import json
import os
import sys

from mcumanifest import builder, constants, validator, workspace


def check_workspace_overwrite(ws_path, force):
    if os.path.exists(ws_path):
        if force:
            return
        if not sys.stdin.isatty():
            print(
                "Error: workspace already exists and stdin is not a terminal.",
                file=sys.stderr,
            )
            sys.exit(1)
        ans = input(f"{ws_path} already exists. Overwrite? [y/N] ").strip().lower()
        if ans != "y":
            print("Aborted.", file=sys.stderr)
            sys.exit(1)


def cmd_init(args):
    check_workspace_overwrite(args.workspace, args.force)

    ws = workspace.init_workspace()
    ws["manifestId"] = args.manifest_id
    ws["minecraftVersion"] = args.mc or args.minecraft_version
    if args.loader:
        ws["loader"] = args.loader
    if args.base_url:
        ws["baseUrl"] = args.base_url
    workspace.save_workspace(ws, args.workspace)
    print(f"Workspace created: {args.workspace}")


def cmd_scan(args):
    ws = workspace.load_workspace(args.workspace)
    with open(args.input, "r", encoding="utf-8") as fh:
        installed = json.load(fh)

    mods_data = installed.get("mods", [])
    ws_mods = ws.setdefault("mods", {})

    for item in mods_data:
        modids = item.get("modIds", [])
        mid = modids[0] if modids else os.path.splitext(item.get("fileName", ""))[0]
        if mid in ws_mods:
            continue
        entry = {
            "name": mid,
            "required": True,
            "variants": [],
        }
        lic = item.get("licenses")
        if lic:
            entry["license"] = lic[0] if isinstance(lic, list) else lic
        ws_mods[mid] = entry

    workspace.save_workspace(ws, args.workspace)
    print(f"Merged {len(ws_mods)} mod items into workspace.")


def _add_variant(args, dl_type):
    ws = workspace.load_workspace(args.workspace)

    # build selector
    sel = workspace.selector_from_values(
        loaders=[args.loader] if args.loader else None,
        operating_systems=[args.os] if args.os else None,
        architectures=[args.arch] if args.arch else None,
    )

    variant = {
        "selector": sel,
        "version": args.version,
        "fileName": os.path.basename(args.file) if args.file else f"{args.modid}.jar",
        "localFile": args.file if args.file else None,
        "download": {},
    }

    if dl_type == "hosted":
        variant["download"]["type"] = "hosted"
        variant["download"]["url"] = args.url
    elif dl_type == "direct":
        url = args.url
        if not url.startswith("http://") and not url.startswith("https://"):
            print(
                "Error: --url must start with http:// or https:// for direct downloads",
                file=sys.stderr,
            )
            sys.exit(1)
        variant["download"]["type"] = "direct"
        variant["download"]["url"] = url
        # optional provider metadata
        provider = getattr(args, "provider", None)
        if provider:
            variant["download"]["provider"] = provider
        project_id = getattr(args, "project_id", None)
        if project_id:
            variant["download"]["projectId"] = project_id
        version_id = getattr(args, "version_id", None)
        if version_id:
            variant["download"]["versionId"] = version_id
    elif dl_type == "manual":
        variant["download"]["type"] = "manual"
        variant["download"]["pageUrl"] = args.page_url
        if args.message:
            variant["download"]["message"] = args.message

    ret = builder.add_or_update_variant(
        ws,
        args.modid,
        sel,
        variant,
        force=args.force,
        no_overwrite=args.no_overwrite,
    )

    if ret == "conflict":
        if args.force:
            pass
        elif args.no_overwrite:
            print(
                "Error: duplicate variant and --no-overwrite specified.",
                file=sys.stderr,
            )
            sys.exit(1)
        else:
            if not sys.stdin.isatty():
                print(
                    "Error: duplicate variant and stdin is not a terminal.",
                    file=sys.stderr,
                )
                sys.exit(1)
            # show old info
            mod_entry = ws["mods"][args.modid]
            idx = next(
                i
                for i, v in enumerate(mod_entry["variants"])
                if workspace.selector_equal(v.get("selector", {}), sel)
            )
            old = mod_entry["variants"][idx]
            print(f"Modid: {args.modid}")
            print(f"Selector: {sel}")
            print(
                f"Old variant: version={old.get('version')}, "
                f"fileName={old.get('fileName')}, "
                f"download type={old.get('download', {}).get('type')}"
            )
            print(
                f"New variant: version={args.version}, "
                f"fileName={variant.get('fileName')}, "
                f"download type={dl_type}"
            )
            ans = input("Overwrite? [y/N] ").strip().lower()
            if ans != "y":
                print("Aborted.", file=sys.stderr)
                sys.exit(1)
            # force overwrite after confirmation
            builder.add_or_update_variant(ws, args.modid, sel, variant, force=True)

    workspace.save_workspace(ws, args.workspace)
    print(f"Variant for '{args.modid}' ({dl_type}) updated.")


def cmd_add_hosted(args):
    _add_variant(args, "hosted")


def cmd_add_direct(args):
    _add_variant(args, "direct")


def cmd_add_manual(args):
    _add_variant(args, "manual")


def cmd_remove(args):
    ws = workspace.load_workspace(args.workspace)
    if args.modid not in ws.get("mods", {}):
        print(f"Mod '{args.modid}' not found", file=sys.stderr)
        sys.exit(1)

    if not any([args.loader, args.os, args.arch]):
        del ws["mods"][args.modid]
    else:
        sel = workspace.selector_from_values(
            loaders=[args.loader] if args.loader else None,
            operating_systems=[args.os] if args.os else None,
            architectures=[args.arch] if args.arch else None,
        )
        variants = ws["mods"][args.modid].get("variants", [])
        new_vars = [
            v
            for v in variants
            if not workspace.selector_equal(v.get("selector", {}), sel)
        ]
        if len(new_vars) == len(variants):
            print("No matching variant found", file=sys.stderr)
            sys.exit(1)
        ws["mods"][args.modid]["variants"] = new_vars
        if not new_vars:
            del ws["mods"][args.modid]

    workspace.save_workspace(ws, args.workspace)
    print(f"Removed '{args.modid}' (selector applied)")


def cmd_list(args):
    ws = workspace.load_workspace(args.workspace)
    mods = ws.get("mods", {})
    if not mods:
        print("No mods in workspace.")
        return
    print(f"{'modid':<30} {'name':<30} {'variants':<4}")
    print("-" * 70)
    for mid, mdata in mods.items():
        name = mdata.get("name", mid)
        vc = len(mdata.get("variants", []))
        print(f"{mid:<30} {name:<30} {vc:<4}")


def cmd_set_license(args):
    ws = workspace.load_workspace(args.workspace)
    mod_entry = ws.get("mods", {}).get(args.modid)
    if not mod_entry:
        print(f"Mod '{args.modid}' not found", file=sys.stderr)
        sys.exit(1)
    mod_entry["license"] = args.license
    if args.allow_redistribution:
        mod_entry["allowRedistribution"] = True
    workspace.save_workspace(ws, args.workspace)
    print(f"License for '{args.modid}' updated.")


def cmd_build(args):
    ws = workspace.load_workspace(args.workspace)
    if args.base_url:
        ws["baseUrl"] = args.base_url
    manifest = builder.build_manifest(ws, allow_redistribution=False)
    schema_path = args.schema or validator._SCHEMA_PATH
    validator.validate_manifest(manifest, schema_path)
    out = args.output or "client-update-manifest.json"
    tmp = out + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, sort_keys=True)
        fh.write("\n")
    os.replace(tmp, out)
    print(f"Manifest written to {out}")


def cmd_validate(args):
    with open(args.manifest, "r", encoding="utf-8") as fh:
        manifest = json.load(fh)
    schema_path = args.schema or validator._SCHEMA_PATH
    validator.validate_manifest(manifest, schema_path)
    print("Manifest is valid.")


FISH_COMPLETION = """\
complete -c mcumanifest -f
complete -c mcumanifest -n "__fish_use_subcommand" -a init -d "Create a new manifest workspace"
complete -c mcumanifest -n "__fish_use_subcommand" -a scan -d "Import installed-mods.json into workspace"
complete -c mcumanifest -n "__fish_use_subcommand" -a add-hosted -d "Add a hosted (self-served) download"
complete -c mcumanifest -n "__fish_use_subcommand" -a add-direct -d "Add a direct-download artifact"
complete -c mcumanifest -n "__fish_use_subcommand" -a add-manual -d "Add a manual update reference"
complete -c mcumanifest -n "__fish_use_subcommand" -a remove -d "Remove a mod or variant"
complete -c mcumanifest -n "__fish_use_subcommand" -a list -d "List mods in workspace"
complete -c mcumanifest -n "__fish_use_subcommand" -a set-license -d "Set license for a mod"
complete -c mcumanifest -n "__fish_use_subcommand" -a build -d "Build the client-update-manifest.json"
complete -c mcumanifest -n "__fish_use_subcommand" -a validate -d "Validate an existing manifest"
complete -c mcumanifest -n "__fish_use_subcommand" -a completion -d "Generate shell completion script"
# init
complete -c mcumanifest -n "__fish_seen_subcommand_from init" -l manifest-id -d "Manifest ID"
complete -c mcumanifest -n "__fish_seen_subcommand_from init" -l mc -l minecraft-version -d "Minecraft version"
complete -c mcumanifest -n "__fish_seen_subcommand_from init" -l loader -r -a "fabric neoforge forge" -d "Mod loader"
complete -c mcumanifest -n "__fish_seen_subcommand_from init" -l base-url -d "Base URL for hosted mods"
complete -c mcumanifest -n "__fish_seen_subcommand_from init" -l force -d "Force overwrite existing workspace without asking"
# generic for add/remove
complete -c mcumanifest -n "__fish_seen_subcommand_from add-hosted add-direct add-manual" -l file -r -F -d "Local JAR file path"
complete -c mcumanifest -n "__fish_seen_subcommand_from add-hosted add-direct add-manual" -l version -d "Version string"
complete -c mcumanifest -n "__fish_seen_subcommand_from add-hosted add-direct add-manual" -l loader -r -a "fabric neoforge forge" -d "Mod loader"
complete -c mcumanifest -n "__fish_seen_subcommand_from add-hosted add-direct add-manual" -l os -r -a "android windows linux macos" -d "Operating system"
complete -c mcumanifest -n "__fish_seen_subcommand_from add-hosted add-direct add-manual" -l arch -r -a "x86_64 x86_32 aarch64 arm32 riscv64 loongarch64" -d "CPU architecture"
complete -c mcumanifest -n "__fish_seen_subcommand_from add-hosted add-direct add-manual" -l force -d "Force overwrite existing variant"
complete -c mcumanifest -n "__fish_seen_subcommand_from add-hosted add-direct add-manual" -l no-overwrite -d "Fail if variant already exists"
# add-specific URL/page-url
complete -c mcumanifest -n "__fish_seen_subcommand_from add-hosted" -l url -r -d "Download URL"
complete -c mcumanifest -n "__fish_seen_subcommand_from add-direct" -l url -r -d "Download URL"
complete -c mcumanifest -n "__fish_seen_subcommand_from add-direct" -l provider -r -a "modrinth curseforge github other" -d "Provider name"
complete -c mcumanifest -n "__fish_seen_subcommand_from add-direct" -l project-id -r -d "Project ID"
complete -c mcumanifest -n "__fish_seen_subcommand_from add-direct" -l version-id -r -d "Version ID on provider"
complete -c mcumanifest -n "__fish_seen_subcommand_from add-manual" -l page-url -r -d "Page URL for manual download"
complete -c mcumanifest -n "__fish_seen_subcommand_from add-manual" -l message -r -d "Extra message for manual update"
# remove
complete -c mcumanifest -n "__fish_seen_subcommand_from remove" -l loader -r -a "fabric neoforge forge" -d "Mod loader"
complete -c mcumanifest -n "__fish_seen_subcommand_from remove" -l os -r -a "android windows linux macos" -d "Operating system"
complete -c mcumanifest -n "__fish_seen_subcommand_from remove" -l arch -r -a "x86_64 x86_32 aarch64 arm32 riscv64 loongarch64" -d "CPU architecture"
# scan
complete -c mcumanifest -n "__fish_seen_subcommand_from scan" -l input -r -F -d "Path to installed-mods.json"
# set-license
complete -c mcumanifest -n "__fish_seen_subcommand_from set-license" -l license -d "License identifier"
complete -c mcumanifest -n "__fish_seen_subcommand_from set-license" -l allow-redistribution -d "Mark as redistribution allowed"
# build / validate
complete -c mcumanifest -n "__fish_seen_subcommand_from build" -l output -r -d "Output path"
complete -c mcumanifest -n "__fish_seen_subcommand_from build" -l schema -r -F -d "Path to JSON Schema file"
complete -c mcumanifest -n "__fish_seen_subcommand_from build" -l base-url -r -d "Override base URL in manifest"
complete -c mcumanifest -n "__fish_seen_subcommand_from validate" -l manifest -r -F -d "Path to manifest JSON for validation"
complete -c mcumanifest -n "__fish_seen_subcommand_from validate" -l schema -r -F -d "Path to JSON Schema file"
"""


def cmd_completion(args):
    shell = args.shell
    if shell == "fish":
        print(FISH_COMPLETION)
    elif shell == "bash":
        # argcomplete auto-registration hook – user should eval this.
        print('eval "$(register-python-argcomplete mcumanifest)"')
    elif shell == "zsh":
        print("autoload -Uz compinit && compinit")
        print('eval "$(register-python-argcomplete mcumanifest)"')
    else:
        print("Unsupported shell", file=sys.stderr)
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(prog="mcumanifest")
    parser.add_argument(
        "--workspace",
        default=workspace.DEFAULT_WORKSPACE_FILE,
        help="Path to the workspace file",
    )
    sub = parser.add_subparsers(dest="command", required=False)

    # init
    p_init = sub.add_parser("init", help="Create a new manifest workspace")
    p_init.add_argument("--manifest-id", required=True, help="Manifest identifier")
    p_init.add_argument("--mc", "--minecraft-version", dest="mc", required=True, help="Minecraft version")
    p_init.add_argument(
        "--loader",
        choices=sorted(constants.SUPPORTED_LOADERS),
        help="Loader target (optional)",
    )
    p_init.add_argument("--base-url", help="Base URL for hosted mods")
    p_init.add_argument("--force", action="store_true", help="Overwrite existing workspace without asking")

    # scan
    p_scan = sub.add_parser("scan", help="Import installed-mods.json into workspace")
    p_scan.add_argument("--input", required=True, help="Path to installed-mods.json")

    # helper: add variant selectors
    def _add_variant_selectors(subparser):
        subparser.add_argument("--loader", choices=sorted(constants.SUPPORTED_LOADERS), help="Mod loader")
        subparser.add_argument("--os", dest="os", choices=sorted(constants.SUPPORTED_OS), help="Operating system")
        subparser.add_argument(
            "--arch",
            dest="arch",
            choices=sorted(constants.SUPPORTED_ARCH),
            help="CPU architecture",
        )
        subparser.add_argument("--force", action="store_true", help="Force overwrite of duplicate variants")
        subparser.add_argument(
            "--no-overwrite",
            action="store_true",
            help="Fail if a duplicate variant already exists",
        )

    # add-*
    p_hosted = sub.add_parser("add-hosted", help="Add a hosted (self-served) download")
    p_hosted.add_argument("modid", help="Mod identifier")
    p_hosted.add_argument("--file", required=True, help="Local JAR file")
    p_hosted.add_argument("--version", required=True, help="Artifact version")
    p_hosted.add_argument("--url", required=True, help="Relative or absolute URL for the hosted file")
    _add_variant_selectors(p_hosted)

    p_direct = sub.add_parser("add-direct", help="Add a direct-download artifact")
    p_direct.add_argument("modid", help="Mod identifier")
    p_direct.add_argument("--file", required=True, help="Local JAR file")
    p_direct.add_argument("--version", required=True, help="Artifact version")
    p_direct.add_argument("--url", required=True, help="Absolute HTTPS(S) URL")
    p_direct.add_argument(
        "--provider",
        choices=["modrinth", "curseforge", "github", "other"],
        help="Provider name (optional)",
    )
    p_direct.add_argument("--project-id", help="Project id on provider (optional)")
    p_direct.add_argument("--version-id", help="Version id on provider (optional)")
    _add_variant_selectors(p_direct)

    p_manual = sub.add_parser("add-manual", help="Add a manual update reference")
    p_manual.add_argument("modid", help="Mod identifier")
    p_manual.add_argument("--file", help="Local JAR file (optional)")
    p_manual.add_argument("--version", required=True, help="Artifact version")
    p_manual.add_argument("--page-url", required=True, help="Page where the user downloads the mod")
    p_manual.add_argument("--message", help="Extra message for the user")
    _add_variant_selectors(p_manual)

    # remove
    p_remove = sub.add_parser("remove", help="Remove a mod or variant")
    p_remove.add_argument("modid", help="Mod identifier")
    p_remove.add_argument("--loader", choices=sorted(constants.SUPPORTED_LOADERS), help="Mod loader")
    p_remove.add_argument("--os", dest="os", choices=sorted(constants.SUPPORTED_OS), help="Operating system")
    p_remove.add_argument("--arch", dest="arch", choices=sorted(constants.SUPPORTED_ARCH), help="CPU architecture")

    # list
    sub.add_parser("list", help="List mods in workspace")

    # set-license
    p_set_lic = sub.add_parser("set-license", help="Set license for a mod")
    p_set_lic.add_argument("modid", help="Mod identifier")
    p_set_lic.add_argument("--license", required=True, help="License SPDX identifier or category")
    p_set_lic.add_argument("--allow-redistribution", action="store_true", help="Mark as redistribution allowed")

    # build
    p_build = sub.add_parser("build", help="Build the client-update-manifest.json")
    p_build.add_argument("--output", default="client-update-manifest.json", help="Output path")
    p_build.add_argument("--schema", help="Path to JSON Schema file")
    p_build.add_argument("--base-url", help="Override base URL in manifest")

    # validate
    p_validate = sub.add_parser("validate", help="Validate an existing manifest")
    p_validate.add_argument("--manifest", required=True, help="Path to manifest JSON")
    p_validate.add_argument("--schema", help="Path to JSON Schema file")

    # completion
    p_comp = sub.add_parser("completion", help="Generate shell completion script")
    p_comp.add_argument("shell", choices=["bash", "zsh", "fish"], help="Target shell")

    args = parser.parse_args()
    if args.command is None:
        parser.print_help()
        sys.exit(1)

    # Dispatch
    dispatch = {
        "init": cmd_init,
        "scan": cmd_scan,
        "add-hosted": cmd_add_hosted,
        "add-direct": cmd_add_direct,
        "add-manual": cmd_add_manual,
        "remove": cmd_remove,
        "list": cmd_list,
        "set-license": cmd_set_license,
        "build": cmd_build,
        "validate": cmd_validate,
        "completion": cmd_completion,
    }
    fn = dispatch.get(args.command)
    if fn is None:
        parser.print_help()
        sys.exit(1)
    fn(args)


if __name__ == "__main__":
    main()
