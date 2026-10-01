# Armed Mobs（武装暴徒）

Minecraft **1.20.1 / Forge 47.x** 模组，包含武装生物、城市结构、城市废土维度及阵营战斗。
本仓库维护自 [glsegg/ArmedMobs](https://github.com/glsegg/ArmedMobs) 的 Fork。
内部模组 ID 为 `tarkovscav`，保持现有存档与配置兼容。

## 安装

使用 `armedmobs-0.1.0.jar`，并安装兼容 1.20.1 的 **GeckoLib 4.7+**。
**TaCZ 1.1.7+** 为可选运行依赖；未安装时，单位使用原版弓或弩。
客户端与服务端使用相同的模组和依赖版本。
第三方语音文件不随公开仓库提供，缺少的语音不会播放。

## 开发与编译

使用 **JDK 17**，在 IDEA 中打开根目录并导入 Gradle 工程。
将 Gradle JVM 设为 JDK 17；开发依赖由 Gradle 下载，不需要仓库内的 `libs` 目录。

```powershell
.\build-mod.bat
.\run-client.bat
```

也可以直接运行 `gradlew.bat jar`、`gradlew.bat genIntellijRuns` 或 `gradlew.bat runClient`。
普通版 JAR 输出到 `build/libs/armedmobs-0.1.0.jar`；开发游戏目录为 `run/`。

## 源码与资源

- `src/main/`：模组 Java 源码和游戏资源。
- `assets_source/`：模型原始资源，包含保留的原始模型变体。
- `gradle/`、`gradlew*` 和 Gradle 配置：编译与开发启动所需文件。

主命令 `/armedmobs`，兼容别名 `/tarkovscav`；配置文件为 `config/tarkovscav-common.toml`。
问题反馈请使用本仓库的 [Issues](https://github.com/EdDYON/Egg-ArmedMobs/issues)。
代码和素材的许可沿用原项目及各自权利人的授权，当前模组元数据为 `All Rights Reserved`。
