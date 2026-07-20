# mcu-manifest-gen — 设计文档

服务端 Minecraft mod，通过命令将已加载 mod 列表导出为 `mcumanifest` 工具兼容的 workspace JSON。

## 基本属性

| 属性 | 值 |
|------|-----|
| modId | `mcu-manifest-gen` |
| 命令根 | `/mcum-gen` |
| 配置文件 | `config/mcu-manifest-gen.json` |
| 输出文件 | 由配置中 `outputPath` 指定，默认 `manifest-workspace.json` |
| 加载器 | Fabric + NeoForge |
| 依赖 | `:common`（复用 `Hashing`） |

## 配置文件

`config/mcu-manifest-gen.json`，首次启动自动生成：

```json
{
  "ignoredMods": ["mcu-manifest-gen"],
  "allowedUsers": [],
  "outputPath": "manifest-workspace.json"
}
```

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `ignoredMods` | `["mcu-manifest-gen"]` | 不写入 workspace 的 modId |
| `allowedUsers` | `[]` | 空 = 仅控制台可执行；填入用户名后对应玩家可在聊天框执行 |
| `outputPath` | `"manifest-workspace.json"` | 输出路径，相对服务端根目录。一个路径对应一个 workspace，反复读写同一个文件 |

## 命令

| 子命令 | 参数 | 说明 |
|--------|------|------|
| `generate` | `[manifestId]` | 扫描已加载 mod，生成/更新 workspace |
| `ignore add` | `<modId>` | 添加到忽略列表 |
| `ignore remove` | `<modId>` | 从忽略列表移除 |
| `ignore list` | — | 列出当前忽略的 modId |

### `generate [manifestId]`

| 场景 | manifestId 行为 |
|------|----------------|
| 指定参数 | 使用指定值，覆盖旧值 |
| 无参数，workspace 中已有 | 保留旧值，不做改动 |
| 无参数，首次创建 | 自动生成 `<mcVersion>-<loader>`（如 `1.21.1-fabric`） |

### Tab 补全

- `ignore add <TAB>` → 列出 `mods/` 下的已加载 mod，排除已忽略的
- `ignore remove <TAB>` → 列出已忽略的 modId

### 权限

- 控制台（`source.getEntity() == null`）：始终可用
- 聊天框：检查玩家名是否在 `allowedUsers` 中
- `/execute as`：以目标玩家身份为准

## `generate` 执行流程

```
1. 读取配置文件
2. 确定 manifestId
3. 获取已加载 mod 列表（PlatformContext.installedMods()）
4. 读取已有 workspace（outputPath 指向的文件，如存在）
5. 遍历已加载 mod（按加载器返回顺序）：

   ┌─ Fabric: JiJ 子 mod 与父 mod 共享同一物理 JAR 路径
   │         → 首次遇到的保留，后续同路径的跳过
   └─ NeoForge: JiJ 子 mod 有独立的 jij: 虚拟路径
                → mods/ 路径检查自动过滤，无需额外处理

   a. origin path 不在 mods/ 下 → 跳过
      （自动过滤：Minecraft 本体、Fabric Loader / NeoForge、libraries/ 中的库）
   b. modId 在 ignoredMods 中 → 跳过
   c. 读取 JAR 文件 → 计算 sha256、sha512、size
   d. 读取 JAR 内 metadata 提取 license：
      ├─ Fabric fabric.mod.json → 存在且非空则填入
      └─ NeoForge META-INF/neoforge.mods.toml → 必填字段，直接填入

6. 合并（见下方合并矩阵）
7. 写回 outputPath
8. 反馈统计信息
```

## 增量合并矩阵

```
Selector 匹配条件（全部满足才算匹配）：
  ├─ selector.loaders         包含当前 loader
  ├─ selector.operatingSystems 包含当前 OS（或未设置）
  └─ selector.architectures    包含当前架构（或未设置）
```

| 服务端状态 | workspace 匹配 variant | `required` | 行为 |
|-----------|----------------------|:---:|------|
| 已安装 | 存在 | — | 更新 version/size/hash/fileName；保留 download/homepage/license |
| 已安装 | 不存在 | — | 新增 variant，selector=`{"loaders":["<当前>"]}`，download.url=`"TODO"` |
| 未安装 | 存在 | `true` | 替换为 `{"selector":{...}, "action":"delete"}` |
| 未安装 | 存在 | `false` | 移除该 variant（若是唯一 variant → 移除整个 mod 条目） |
| — | 不匹配 | — | **原样保留，不进行任何操作** |

## 反馈示例

```
[MCUManifestGen] Scanning 25 loaded mods...
[MCUManifestGen] Manifest ID: my-pack-1.21.1
[MCUManifestGen] Skipped 9 non-mod-folder (minecraft, fabricloader, neoforge, ...)
[MCUManifestGen] Skipped 2 ignored (mcu-manifest-gen, lithium)
[MCUManifestGen] Skipped 1 duplicate JAR (fabric-api-base)
[MCUManifestGen] License from JAR metadata: sodium=LGPL-3.0, iris=LGPL-3.0
[MCUManifestGen] Wrote 11 mods (5 updated, 4 added, 2 marked DELETE) to manifest-workspace.json
```

## 生成的 workspace 条目格式

```json
{
  "manifestId": "1.21.1-fabric",
  "mods": {
    "sodium": {
      "name": "Sodium",
      "required": true,
      "license": "LGPL-3.0",
      "variants": [
        {
          "selector": { "loaders": ["fabric"] },
          "version": "0.5.11+mc1.21.1",
          "fileName": "sodium-fabric-0.5.11+mc1.21.1.jar",
          "size": 1024000,
          "hashes": {
            "sha256": "e720c099...",
            "sha512": "c1a04ef3..."
          },
          "download": { "type": "hosted", "url": "TODO" }
        }
      ]
    }
  }
}
```

| 字段 | 来源 | 人需补？ |
|------|------|:---:|
| `manifestId` | 参数指定或自动生成 `<mcVersion>-<loader>` | |
| `name` | 加载器 metadata | |
| `required` | 默认 `true` | |
| `license` | JAR 内 metadata（Fabric 可选，NeoForge 必填） | 仅 Fabric 为空时 |
| `selector.loaders` | 当前加载器 | |
| `version` | 加载器 metadata | |
| `fileName` | JAR 文件名 | |
| `size` | `Files.size()` | |
| `hashes` | `Hashing.hashes()` | |
| `download.url` | `"TODO"` | ✅ |
| `download.type` | `"hosted"`（默认） | ✅ |
| `homepage` | — | ✅ |
| `selector.(os/arch)` | — | ✅（native mod） |
| `selector`（其他 loader） | — | ✅（多 loader 包） |

## 完整工作流

```
# 1. 安装 mod，启动服务端
# 2. 忽略服务端独占 mod
/mcum-gen ignore add lithium
/mcum-gen ignore add carpet

# 3. 生成 workspace
/mcum-gen generate my-pack-1.21.1

# 4. 复制到本地
scp server:~/manifest-workspace.json tools/manifest-generator/

# 5. 用 mcumanifest 补全信息
mcumanifest add-hosted sodium --url mods/sodium-fabric-0.5.11.jar

# 6. 构建 manifest
mcumanifest build

# 7. 部署到更新服务器
```

## 项目结构

```
mc-client-update/
├── common/              ← 现有，复用 Hashing
├── fabric/              ← 现有（客户端）
├── neoforge/            ← 现有（客户端）
├── server-fabric/       ← 新增：Fabric 服务端入口
│   ├── build.gradle           （Fabric Loom + 依赖 :common）
│   └── src/main/java/.../server/fabric/
└── server-neoforge/     ← 新增：NeoForge 服务端入口
    ├── build.gradle           （NeoForge moddev + 依赖 :common）
    └── src/main/java/.../server/neoforge/
```
