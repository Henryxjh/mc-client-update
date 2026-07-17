# MC Client Update

Minecraft 1.21.1 客户端 Mod 更新器，支持 Fabric 和 NeoForge，目标是按照私人服务器提供的版本清单在游戏启动阶段更新指定 Mod。

## 项目结构

- `common`：纯 Java 更新事务、哈希校验和后续的服务器清单逻辑。
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

## 平台目标

清单中的平台相关文件应使用 `加载器-系统-架构` 目标标识。当前系统标识为
`android`、`windows`、`linux`、`macos`，架构标识为 `x86_64`、`x86_32`、
`aarch64`、`arm32` 和 `riscv64`。无法识别的值使用 `unknown`，不应自动降级到
其他架构的 native 文件。

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
主 `modid` 为键，并可配置多个平台变体。变体的 `selector` 未填写某个维度时表示
该维度不受限制；匹配多个变体时选择 `priority` 最大的一个，最高优先级并列应视为
无效清单，而不是依赖数组顺序猜测。

三种下载方式为：

- `hosted`：由清单服务器托管；相对 `url` 使用顶层 `baseUrl` 解析，未配置
  `baseUrl` 时相对清单自身 URL 解析；
- `direct`：使用 Modrinth、CurseForge、GitHub 或作者提供的外部直链；
- `manual`：只展示作者下载页面和提示，不自动下载。

`version` 用于展示，是否已经安装目标文件必须以 `sha256` 为准。下载后还必须同时
校验 `size` 和 `sha256`，校验通过后才能进入 JAR 替换事务。`provider`、`projectId`、
`versionId` 和 `license` 都是可选的来源记录字段，客户端不应依赖它们完成下载，
因此自己拥有版权的闭源 Mod 使用 `hosted` 时无需填写这些字段。

示例中的重复字符哈希、`example.invalid` 地址和标有 `replace-with-generated-*` 的值
仅用于展示结构，不能直接作为生产清单发布；生成脚本必须用实际文件元数据替换。

服务器更新清单的地址、认证和 JSON 格式尚未确定，因此目前没有发起网络请求。

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
