# 本地开发依赖

将 TaCZ JAR 放在此目录：

```text
libs/tacz-1.20.1-1.1.8-hotfix.jar
```

编译需要 TaCZ API；玩家运行时 TaCZ 可选，未安装时单位使用弓或弩。
更换开发依赖版本时，修改 `gradle.properties` 的 `tacz_module` 和 `tacz_version`。
Gradle 使用 `fg.deobf` 生成开发映射，TaCZ 不包含在本模组 JAR 中。
第三方 JAR 不提交到仓库。
