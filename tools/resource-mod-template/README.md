# Resource Mod Template

通用资源 mod 模板。将资源文件打包成 JAR，通过 `mc-client-update` 管理更新。
添加资源**不需要改任何代码**。

## 原理

| JAR 内目录 | 行为 |
|-----------|------|
| `assets/` | Minecraft 自动加载 |
| `data/` | Minecraft 自动加载 |
| `resource-pack/<name>/` (含 `pack.mcmeta`) | 自动注册为内置资源包 |
| `override/` | 提取到游戏目录（镜像路径），仅当 `.resource-lock` 变更时触发 |

- **提取标记**：JAR 内 `.resource-lock` 存放随机 UUID；游戏目录下 `.resource-extracted-<modid>` 记录上次提取的 UUID。一致则跳过。
- **Fabric 入口**：运行时检测 modId，无需占位符
- **NeoForge 入口**：`@Mod("{{MODID}}")`，由 `pack.py` 替换
- **版本号**：由 `build.gradle` 的 `version` 字段管理

## 项目结构

```
resource-mod-template/
├── settings.gradle                 ← 独立 Gradle 项目，包含三个子模块
├── build.gradle                    ← 根构建，version = '1.0.0'
├── pack.py                         ← 构建脚本
├── README.md
├── common/
│   ├── build.gradle                ← 纯 java-library
│   └── src/main/java/.../
│       └── ResourceExtractor.java  ← 提取 + 资源包发现，无 loader 依赖
├── fabric/
│   ├── build.gradle                ← Fabric Loom，依赖 :common
│   └── src/main/
│       ├── java/.../
│       │   └── FabricEntrypoint.java ← implements ModInitializer，运行时检测 modId
│       └── resources/
│           └── fabric.mod.json     ← {{MODID}} {{VERSION}} 占位
└── neoforge/
    ├── build.gradle                ← NeoForge moddev，依赖 :common
    └── src/main/
        ├── java/.../
        │   └── NeoForgeEntrypoint.java ← @Mod，{{MODID}} 占位（pack.py 替换）
        └── resources/
            └── META-INF/
                └── neoforge.mods.toml ← {{MODID}} {{VERSION}} 占位
```

`pack.py` 构建时：
1. 复制模板到临时目录 → 替换 `{{MODID}}` `{{VERSION}}` → `gradlew buildAll`
2. 收集 `fabric/build/libs/*.jar` 和 `neoforge/build/libs/*.jar`
3. 以 Fabric JAR 为底，合并 NeoForge JAR 中独有的条目
4. 写入 `.resource-lock`（随机 UUID）→ 追加资源文件 → 输出

## 使用方法

### 准备资源

```
my-files/
├── assets/                  ← 选填
│   └── minecraft/textures/...
├── data/                    ← 选填
│   └── minecraft/recipes/...
├── resource-pack/           ← 选填：自动注册为内置资源包
│   └── MyPack/
│       ├── pack.mcmeta
│       └── assets/...
└── override/                ← 选填：提取到游戏目录
    ├── shaderpacks/MyShader.zip
    └── config/sodium-options.properties
```

### 打包

```bash
python tools/resource-mod-template/pack.py \
  --modid resource-my-pack \
  --resources ./my-files/
```

产物：`resource-my-pack.jar`

### 工作流

```
1. 准备资源 → my-files/
2. pack.py → resource-my-pack.jar
3. 放到更新服务器
4. mcu-manifest-gen 发现 → 写入 workspace
5. mcumanifest 补 download URL → build → 部署 manifest
6. 客户端 mc-client-update 自动下载安装
```
