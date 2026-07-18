# MC Client Update

Minecraft 1.21.1 客户端 Mod 更新器，支持 Fabric 和 NeoForge，目标是按照私人服务器提供的版本清单在游戏启动阶段更新指定 Mod。

## 项目结构

- `common`：纯 Java 更新事务、哈希校验、服务器清单拉取与解析逻辑。
- `fabric`：Fabric Loader 入口，不依赖 Fabric API。
- `neoforge`：NeoForge 入口。
- `test-mod`：早期跨平台文件系统测试项目，已被 Git 忽略。

## 当前状态

已经实现并测试构建：

- Java 21 / Minecraft 1.21.1 双加载器工程；
- SHA-256 文件校验；
- 同目录候选 JAR 约束；
- `当前 JAR -> 非 .jar 备份 -> 候选 JAR` 的事务式替换；
- 原子移动不可用时的普通重命名降级；
- 安装失败回滚和下一次启动清理备份的接口；
- 更新完成后可由启动线程抛出的 `RestartRequiredException`。
- 运行平台检测，能够区分桌面 Linux 与将 `os.name` 报告为 Linux 的 Android JVM；
- 稳定的下载目标标识，例如 `fabric-windows-x86_64` 和 `neoforge-android-aarch64`。
- 从配置的 `manifestUrl` 拉取更新清单并执行完整的 schema 及语义验证（包括版本、时间戳、过期、必需字段、modid 格式、选择器枚举、下载类型等）。
- **已实现** Mod 扫描及 variant 匹配，已下载到暂存区（`.mc-client-update/downloads/sha512-<HASH>/<fileName>` 或 `sha256-<HASH>/<fileName>`），并在下载完成后安装到 mods 目录。

**已实现** 根据更新候选下载 artifact **并安装到游戏目录**：
  - 三种下载方式：`hosted`（相对 URL 可使用 `baseUrl` 或 manifest URI 解析）、`direct`（绝对直链）、`manual`（不自动下载，仅记录）。
  - 相同 artifact 的多个候选去重后只下载一次，结果中保留所有关联的 modId。
  - 下载缓存于 `.mc-client-update/downloads/<sha512-/sha256-HASH>/<fileName>`，命中后跳过网络。
  - 下载失败不中断其他 artifact，InterruptedException 会安全停止后续下载。
  - 每次扫描结束后自动写入 `config/mc-client-update-download-report.json`（原子覆盖），包含成功、失败和手动更新的细节。
  - **安装阶段已完成**：通过 `ArtifactInstaller` 把下载的文件放入 mods 目录；`HASH_MISMATCH` 使用 `JarTransaction` 替换现有 JAR；`MISSING_REQUIRED` 在不存在冲突时新增 JAR；冲突、校验失败及 `InterruptedException` 均记录到报告且不会中断后续安装。
  - 安装成功后抛出 `RestartRequiredException` 提示重启；有下载/手动/安装失败但没有安装成功时阻止继续启动。
  - 若有手动安装、下载失败、安装失败或安装成功，退出前 Swing 弹窗提示列出条目，关闭后游戏继续退出，避免半更新状态。
  - 提醒文本始终由无 Swing 依赖的 `UpdateAttentionMessage` 生成并写入游戏日志，Swing 不可用时日志依然完整。
  - 抛出的 `RestartRequiredException` 和 `DownloadException` 消息也包含相同的提醒文本，供启动器展示。

## 平台目标

清单中的平台相关文件应使用 `加载器-系统-架构` 目标标识。当前系统标识为
`android`、`windows`、`linux`、`macos`，架构标识为 `x86_64`、`x86_32`、
`aarch64`、`arm32`、`riscv64` 和 `loongarch64`。无法识别的值使用 `unknown`，不应自动降级到
其他架构的 native 文件。清单选择器中的 `loaders` 字段支持 `fabric`、`neoforge` 和 `forge`，
当前项目仅提供 Fabric 与 NeoForge 入口，`forge` 是为未来清单兼容保留的值，运行时实际的匹配依据
`PlatformContext.loaderName()`。

Android 检测不只依赖 `os.name`。它还会检查 `os.version`、Java VM/运行时信息以及
`android.os.Build`，因此能够识别常见启动器报告的 `Linux / Android-16 / aarch64`。

## 客户端配置

首次启动会创建 `config/mc-client-update.json`：

```json
{
  "manifestUrl": "",
  "connectTimeoutSeconds": 10,
  "readTimeoutSeconds": 30,
  "allowInsecureHttp": false
}
```

填写 `manifestUrl` 后启用启动检查；留空时不联网。更新地址默认必须使用 HTTPS。
`allowInsecureHttp` 仅用于可信局域网或本机测试，不建议分发给玩家时启用。超时范围为
1 到 300 秒。配置文件存在但格式或地址无效时启动会明确失败，避免错误配置导致客户
端在不知情的情况下跳过强制更新。

## 更新清单

清单的 [JSON Schema](docs/client-update-manifest.schema.json) 和
[完整示例](docs/client-update-manifest.example.json) 位于 `docs` 目录。每个 Mod 以
主 `modid` 为键，并可配置多个平台变体。`action` 字段默认为 `"install"`，设为 `"delete"`
时将在启动时移除已安装的 Mod（此时 `variants` 可以省略或为空数组）。变体的 `selector`
未填写某个维度时表示该维度不受限制；匹配多个变体时选择 `priority` 最大的一个，最高
优先级并列应视为无效清单，而不是依赖数组顺序猜测。

可选的顶层字段 `minimumLoaderVersions` 指定此清单要求的最低加载器版本。它是一个对象，
必须恰好包含一个键，键为加载器 ID（允许 `fabric`、`neoforge`、`forge`），
值为最低版本号（例如 `0.16.0`）。如果同一整合包需要针对不同加载器指定不同的最低版本，
应发布不同的 manifest 或者不使用此顶层约束。
启动时如果清单包含此字段，而当前加载器 ID 不等于该键，或版本低于指定值，则更新过程会终止并显示错误。
若顶层未提供此字段，不做版本约束，行为不变。

三种下载方式为：

- `hosted`：由清单服务器托管；相对 `url` 使用顶层 `baseUrl` 解析，未配置
  `baseUrl` 时相对清单自身 URL 解析；
- `direct`：使用 Modrinth、CurseForge、GitHub 或作者提供的外部直链；
- `manual`：只展示作者下载页面和提示，不自动下载。

`version` 用于展示，是否已经安装目标文件必须以哈希为准。`sha256` 和 `sha512`
可以任选其一，也可以同时提供，但 `hashes` 中至少要有一种。下载后必须校验 `size`
和清单提供的全部哈希，全部通过后才能进入 JAR 替换事务。`provider`、`projectId`、
`versionId` 和 `license` 都是可选的来源记录字段，客户端不应依赖它们完成下载，
因此自己拥有版权的闭源 Mod 使用 `hosted` 时无需填写这些字段。

示例中的重复字符哈希、`example.invalid` 地址和标有 `replace-with-generated-*` 的值
仅用于展示结构，不能直接作为生产清单发布；生成脚本必须用实际文件元数据替换。

配置 `manifestUrl` 后启动时会拉取更新清单、扫描已安装 Mod、下载更新到 `.mc-client-update/downloads/` 暂存区并自动写入 `config/mc-client-update-download-report.json`。

## 构建

```bash
./gradlew buildAll
```

Windows：

```bat
gradlew.bat buildAll
```

产物位于：

- `fabric/build/libs/mc-client-update-fabric-0.1.0.jar`
- `neoforge/build/libs/mc-client-update-neoforge-0.1.0.jar`
