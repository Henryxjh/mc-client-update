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
