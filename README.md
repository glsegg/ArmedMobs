# Armed Mobs（武装暴徒）

![Armed Mobs](src/main/resources/armedmobs-logo.png)

Minecraft **1.20.1 / Forge** 模组。九种武装单位在城市中作战，支持掩体、压制、撤退、
小队协同、投掷物与指挥道具，包含城市结构、城市废土维度和主世界占领战。

内部模组 ID 保持为 `tarkovscav`，以兼容现有存档、资源与配置。

## 安装

将 `armedmobs-0.1.0.jar` 放入游戏的 `mods` 目录，并安装：

- **Forge 1.20.1 / 47.x**。
- **GeckoLib Forge 1.20.1**，版本 **4.7+**，开发环境使用 **4.8.4**。
- **TaCZ 1.1.7+（可选）**：安装后使用 TaCZ 枪械；未安装时使用原版弓或弩。

客户端和服务端均需安装本模组及 GeckoLib；使用枪械时两端都安装 TaCZ。
普通版 JAR 不内置 GeckoLib。另行构建的 `-all.jar` 内置 GeckoLib，两个版本只安装一个。
第三方语音文件不随公开仓库提供；缺少这些音频时，对应语音不会播放。

## 开发与编译

使用 **JDK 17**。将 TaCZ 开发依赖放到 `libs/tacz-1.20.1-1.1.8-hotfix.jar`，
更换版本时同步修改 `gradle.properties` 中的 `tacz_module` / `tacz_version`。
TaCZ 是运行时可选依赖，但编译本项目需要其 API。

在 IDEA 中打开仓库根目录，导入 Gradle 工程，设置 Gradle JVM 为 JDK 17。

```powershell
.\gradlew.bat build
```

普通版输出到 `build/libs/armedmobs-0.1.0.jar`。

```powershell
.\gradlew.bat genIntellijRuns
.\gradlew.bat runClient
```

也可在 IDEA 的 Gradle 面板运行 `runClient`。开发游戏目录为 `run/`；
构建产物、本地依赖、IDE 设置和运行目录不提交到仓库。

## 命令与配置

主命令为 `/armedmobs`，兼容别名为 `/tarkovscav`。服务端命令需要权限等级 2。

```mcfunction
/armedmobs city add test 64
/armedmobs spawn scav
/armedmobs debug
/armedmobs dimension
```

配置文件：`config/tarkovscav-common.toml`。客户端可用 `/armedmobs client reload` 重读配置。
完整命令、配置默认值、实体 ID 及扩展接口见 [命令与配置参考](docs/COMMAND_AND_CONFIG_REFERENCE.md)。

## 源码与资源

- `src/`：Java 源码及游戏资源。
- `assets_source/`：模型原始资源。
- `tools/`：有效测试、资源生成器与开发工具。
- `gradle/`、`gradlew*`：Gradle Wrapper。

使用资源生成器前保留原始素材；生成结果按各工具说明输出。
