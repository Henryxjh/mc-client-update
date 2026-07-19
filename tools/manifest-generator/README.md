# Manifest 生成器规格

> 当前状态：本目录已经包含 Python 工具 `mcumanifest` 的初版实现和测试。

## 当前实现

初版已实现：

- `pyproject.toml` 和 `mcumanifest` 命令行入口。
- `manifest-workspace.json` 读写。
- `init`、`scan`、`add-hosted`、`add-direct`、`add-manual`、`remove`、`list`、`set-license`、`set-version-policy`、`build`、`validate`、`completion` 子命令。
- selector 枚举：loader、OS、CPU 架构（含 `loongarch64`）。
- 重复 modid + selector 的冲突处理：非交互失败，`--force` 覆盖，`--no-overwrite` 失败。
- `sha256` / `sha512` 与 size 计算。
- hosted/direct/manual 三种下载类型。
- hosted license 策略检查，空 license 允许 hosted，常见开源协议允许 hosted，ARR/Custom/Unknown/未识别协议默认禁止，`allowRedistribution` 可覆盖。
- `baseUrl`、`minimumLoaderVersions` 等 manifest 顶层字段透传。
- fish completion 静态脚本输出。

运行测试：

```bash
PYTHONPATH=tools/manifest-generator/src python -m pytest tools/manifest-generator/tests
```

## 目标

`mcumanifest` 用于生成客户端更新器消费的 `client-update-manifest.json`。

设计边界：

- 客户端 mod 只执行 manifest，不在客户端调用 Modrinth、CurseForge、GitHub 等平台 API 查找文件。
- 平台 API、下载地址选择、license/再分发检查放在生成器或人工流程中完成。
- 生成器以命令行交互为主，避免让用户手写大段 JSON。
- 生成结果必须符合仓库中的 [client-update-manifest.schema.json](../../docs/client-update-manifest.schema.json)。

## Python 项目根目录

本目录 `tools/manifest-generator/` 是未来 Python 项目根目录。推荐最终结构：

```text
tools/manifest-generator/
├── README.md
├── pyproject.toml
├── src/
│   └── mcumanifest/
│       ├── __init__.py
│       ├── cli.py
│       ├── workspace.py
│       ├── collector.py
│       ├── jar_metadata.py
│       ├── builder.py
│       ├── validator.py
│       ├── licenses.py
│       └── completers.py
└── tests/
    ├── test_workspace.py
    ├── test_builder.py
    ├── test_duplicates.py
    └── test_licenses.py
```

## 输入与输出

### `manifest-workspace.json`

生成器维护的中间状态文件。用户不应主要手写它，而是通过 CLI 命令修改。

workspace 可以使用更便于编辑的内部字段，例如：

- `downloadType`
- `localFile`
- `allowRedistribution`
- `selector`
- `license`
- `source`

构建最终 manifest 时必须映射为当前客户端 schema：

- workspace `downloadType` → manifest `artifact.download.type`
- workspace 下载 URL / 页面 URL → manifest `artifact.download.url` 或 `artifact.download.pageUrl`
- 自动计算的文件大小 → manifest `artifact.size`
- 自动计算的哈希 → manifest `artifact.hashes.sha256` / `artifact.hashes.sha512`

### `installed-mods.json`

游戏内采集工具输出。生成器可读取它作为 modid、文件名、版本和 license 的事实来源。

示例：

```json
{
  "loader": "neoforge",
  "minecraftVersion": "1.21.1",
  "loaderVersion": "21.1.200",
  "mods": [
    {
      "modIds": ["create"],
      "fileName": "create-1.21.1-6.0.0.jar",
      "path": "mods/create-1.21.1-6.0.0.jar",
      "version": "6.0.0",
      "licenses": ["MIT"]
    }
  ]
}
```

采集工具负责从真实 loader 环境读取：

- `loader`
- `minecraftVersion`
- `loaderVersion`
- `modIds`
- `fileName`
- `path`
- `version`
- `licenses`

Python 生成器负责合并：

- 下载类型与下载地址
- 平台 selector
- required/manual/delete 策略
- hash/size
- license/再分发策略

### `client-update-manifest.json`

最终输出文件。要求：

- JSON key 顺序稳定，便于 `git diff`。
- modid 按自然顺序输出。
- variant 顺序稳定；优先级相同且 selector 冲突时应报错。
- 输出后立即执行 schema 校验和额外语义校验。

## CLI 设计

工具名建议：`mcumanifest`。

必须支持的子命令：

```bash
mcumanifest init
mcumanifest scan
mcumanifest add-hosted
mcumanifest add-direct
mcumanifest add-manual
mcumanifest remove
mcumanifest list
mcumanifest set-license
mcumanifest set-version-policy
mcumanifest build
mcumanifest validate
mcumanifest completion
```

建议命令示例：

```bash
mcumanifest init --manifest-id friends-1.21.1 --mc 1.21.1 --loader neoforge
mcumanifest scan --input installed-mods.json
mcumanifest add-hosted create --file mods/create.jar --url mods/create.jar --loader neoforge
mcumanifest add-direct sodium --file mods/sodium.jar --url https://cdn.example.com/sodium.jar --loader fabric
mcumanifest add-manual restricted_mod --file mods/restricted.jar --page-url https://author.example/download
mcumanifest remove create --loader neoforge --os linux --arch x86_64
mcumanifest list
mcumanifest set-license create MIT --allow-redistribution
mcumanifest set-version-policy create --skip-if-installed-version-greater-than 1.2.0
mcumanifest set-version-policy create --clear-skip-if-installed-version-greater-than
mcumanifest build --base-url https://cdn.example.com/mc/1.21.1/ --output client-update-manifest.json
mcumanifest validate --manifest client-update-manifest.json --schema ../../docs/client-update-manifest.schema.json
mcumanifest completion bash
mcumanifest completion zsh
mcumanifest completion fish
```

### 子命令职责

- `init`：创建 `manifest-workspace.json`。若文件已存在，必须询问是否覆盖；非交互环境默认失败。
- `scan`：读取 `installed-mods.json`，把采集到的 mod 合并进 workspace。
- `add-hosted`：添加托管在自有 HTTP/HTTPS 服务上的文件。
- `add-direct`：添加绝对直链下载文件。`--file` 可选；若省略 `--file`，在 `build` 阶段会从 `--url` 临时下载文件以计算 `size`、`sha256`、`sha512`；`fileName` 将由 URL 路径的 basename 自动推断，若无 basename 则使用 `<modid>.jar`。
- `add-manual`：添加手动更新项，不生成自动下载 URL。
- `remove`：删除指定 modid，或删除指定 selector 对应的 variant。
- `list`：以表格展示当前 workspace。
- `set-license`：修改 license 和 `allowRedistribution`。
- `set-version-policy`：设置或清除 `skipIfInstalledVersionGreaterThan` 字段，用于控制当已安装版本大于指定值时跳过更新。
- `build`：计算 hash/size，生成最终 manifest，并执行校验。默认输出每个 artifact 的处理进度；可用 `--no-progress` 关闭。
- `validate`：只校验现有 manifest 或 workspace。
- `completion`：输出 shell 自动补全脚本，至少支持 bash、zsh、fish。

## 自动补全

CLI 使用：

- `argparse` 负责命令解析。
- `argcomplete` 负责 bash/zsh 自动补全。
- fish 应输出原生 `complete` 脚本；不要要求 fish 用户加载 bash completion。

补全必须覆盖：

- 子命令。
- 参数名。
- `--file` / `--input` / `--output` 的文件路径。
- loader 枚举：`fabric`、`neoforge`、`forge`。
- OS 枚举：`android`、`windows`、`linux`、`macos`。
- CPU 架构枚举：`x86_64`、`x86_32`、`aarch64`、`arm32`、`riscv64`、`loongarch64`。
- workspace 中已有的 modid，用于 `remove`、`set-license` 等命令。

安装补全的推荐形式：

```bash
eval "$(register-python-argcomplete mcumanifest)"
```

也可以支持静态脚本输出：

```bash
mcumanifest completion bash > ~/.local/share/bash-completion/completions/mcumanifest
mcumanifest completion zsh > ~/.zfunc/_mcumanifest
```

fish 补全必须能生成到用户配置目录：

```fish
mcumanifest completion fish > ~/.config/fish/completions/mcumanifest.fish
```

fish 版本至少需要补全：

- 子命令。
- 参数名。
- `--loader`、`--os`、`--arch` 枚举。
- `--file`、`--input`、`--output` 路径。
- workspace 中已有 modid，用于 `remove`、`set-license`。

## selector 规则

selector 与客户端 manifest schema 保持一致。

允许字段：

- `loaders`
- `operatingSystems`
- `architectures`

CLI 参数建议：

- `--loader fabric`
- `--os android`
- `--arch aarch64`

允许枚举：

```text
loader: fabric, neoforge, forge
os:     android, windows, linux, macos
arch:   x86_64, x86_32, aarch64, arm32, riscv64, loongarch64
```

空 selector 表示不限制该维度。所有 selector 在最终 manifest 中必须转换为 schema 使用的数组字段，例如：

```json
{
  "selector": {
    "loaders": ["neoforge"],
    "operatingSystems": ["android"],
    "architectures": ["aarch64"]
  }
}
```

## 重复添加与覆盖

重复判断规则：

```text
同 modid + 同 selector = 覆盖候选
同 modid + 不同 selector = 追加 variant
不同 modid = 新增 mod
```

覆盖候选的行为：

- 交互终端中询问是否覆盖，默认 `No`。
- 非交互环境默认失败。
- `--force`：直接覆盖。
- `--no-overwrite`：重复时直接失败。
- `--append-variant`：明确表示同 modid 下追加平台 variant；如果 selector 仍重复，则失败或询问覆盖。

询问内容必须展示旧值和新值，至少包括：

- modid
- selector
- fileName
- version
- download type
- URL / pageUrl
- license

## 下载类型

### hosted

用于自有服务器或 NGINX/CDN 托管文件。

CLI 示例：

```bash
mcumanifest add-hosted create --file mods/create.jar --url mods/create.jar --loader neoforge
```

规则：

- `--file` 指向本地 jar，用于读取元数据并计算 hash/size。
- `--url` 可以是相对路径；最终 manifest 中由 `baseUrl` 或 manifest URL 解析。
- 需要通过 license/再分发检查。

最终 manifest 应输出：

```json
{
  "download": {
    "type": "hosted",
    "url": "mods/create.jar"
  }
}
```

### direct

用于作者/CDN/平台提供的绝对直链。

CLI 示例：

```bash
mcumanifest add-direct sodium --file mods/sodium.jar --url https://cdn.example.com/sodium.jar --loader fabric
```

或省略本地文件，由生成器自行下载：

```bash
mcumanifest add-direct sodium --url https://cdn.example.com/sodium.jar --loader fabric --version 1.0.5
```

规则：

- `--url` 必须是绝对 HTTP(S) URL。
- `--file` 可选；如果提供，则用于本地计算 hash/size。若省略，`build` 阶段会从 `--url` 下载文件到临时目录以计算 `size`、`sha256`、`sha512`，完成后立即清理；`fileName` 将从 URL 路径的 basename 自动推断，若 URL 路径无 basename 则使用 `<modid>.jar`。
- 可选记录 `provider`、`projectId`、`versionId`。

### manual

用于不允许自动再分发或需要用户手动下载的 mod。

CLI 示例：

```bash
mcumanifest add-manual restricted_mod --file mods/restricted.jar --page-url https://author.example/download
```

规则：

- 不生成自动下载 URL。
- 最终 manifest 使用 `download.type = "manual"`。
- 必须提供 `pageUrl`。
- 可选 `message` 用于告诉玩家如何下载。

## hash 与 size

生成器必须自动计算：

- `artifact.size`
- `artifact.hashes.sha256`
- `artifact.hashes.sha512`

默认同时输出 sha256 和 sha512。后续可以提供参数只输出其中一种，但至少必须输出一种。

同一文件多次使用时，应复用同一 hash 结果，避免重复计算。

## license 与再分发检查

默认 license 来源：

1. 游戏内采集工具输出的 `licenses`。
2. jar 元数据。
3. CLI 显式参数。
4. workspace override。

默认策略：

- 常见开源协议允许 `hosted`，例如 `MIT`、`Apache-2.0`、`LGPL-3.0-only`、`LGPL-3.0-or-later`、`GPL-3.0-only`、`MPL-2.0`、`BSD-2-Clause`、`BSD-3-Clause`、`CC0-1.0`、`Unlicense`。
- 空 license 允许 `hosted`，表示这是发布者自有/私有 mod，生成器不应要求填写公开许可证。
- `All Rights Reserved`、`ARR`、`Custom`、`Unknown` 默认不允许 `hosted`。
- 不允许 hosted 时，必须使用 `manual`，或显式设置 `allowRedistribution = true`。

显式覆盖命令：

```bash
mcumanifest set-license some_mod "All Rights Reserved"
mcumanifest set-license own_closed_mod "Custom" --allow-redistribution
```

生成器只做发布前检查；客户端更新 mod 不基于 license 做运行时决策。

## 校验

### JSON Schema 校验

`build` 和 `validate` 必须使用仓库中的 schema：

```text
../../docs/client-update-manifest.schema.json
```

校验失败时，错误信息必须指出字段路径。

### 额外语义校验

必须检查：

- manifest `minecraftVersion` 是否存在。
- workspace loader/os/arch 是否都属于客户端 schema 枚举。
- `modIds` 与 jar 元数据是否一致。
- 同 modid + 同 selector 是否重复。
- 同 selector 下 variant priority 是否冲突。
- 本地文件是否存在。
- 文件名是否以 `.jar` 结尾。
- `hosted` 是否缺少 URL。
- `direct` URL 是否不是绝对 HTTP(S)。
- `manual` 是否缺少 `pageUrl`。
- hash/size 是否与本地文件一致。
- license 策略是否允许当前下载类型。

错误信息必须包含：

- modid
- selector
- 文件路径
- 字段名
- 失败原因

## 错误处理原则

- 危险操作默认失败。
- 非交互环境不弹询问，直接失败。
- 所有覆盖行为必须由交互确认或 `--force` 明确授权。
- 生成失败时不能写出半成品 manifest；应写入临时文件并原子替换。
- 错误输出到 stderr。
- 正常机器可读输出后续可支持 `--json`。

## 第一版实现优先级

1. `pyproject.toml` 和 `argparse` CLI 框架。
2. workspace 读写。
3. `add-hosted` / `add-direct` / `add-manual`。
4. 重复 selector 覆盖询问。
5. hash/size 计算。
6. `build` 输出 manifest。
7. schema 校验。
8. bash/zsh/fish 补全。
9. 读取 `installed-mods.json`。
10. license 策略检查。

本文档是后续实现 `mcumanifest` 的功能规格；实现代码应以这里的行为为准。
