# BBS Geo Model Importer

Minecraft Forge 1.20.1 的 BBS 客户端附属 Mod。游戏启动时，它从已加载的 Mod 和解压的资源文件夹中寻找 Geo 模型、贴图和动画，并把可识别的模型按来源加入 BBS。按 B 打开模型列表或滚动列表时，会复用启动时建立的索引。

## 安装与使用

1. 在装有 BBS 的 Forge 1.20.1 客户端中，将附属 Mod 的 JAR 放进当前游戏实例的 `mods` 目录。[下载 Forge 版](https://github.com/MKUXQQ/bbs-geo-importer/releases/latest)
2. 如果模型来自其他 Minecraft 版本，把那个 Mod 的 JAR 解压成一个独立文件夹，保留原有目录结构。例如，`mods/example-mod/assets/example/geo/creature.geo.json`。附属 Mod 只读取这个解压包的资源。
3. 启动游戏，按 B 查看模型。BBS 设置右侧的文件夹图标可以打开「Mod 模型管理」，按来源显示或隐藏模型。

导入的文件位于当前实例的 `config/bbs/assets/models/`。模型缺图时，查看 `config/bbs/model-import-report.json` 中对应 Geo 的记录。导入器会保留有明确关联的底图和材质。依赖运行时玩家皮肤、自定义渲染器或发光叠加层的效果可能无法完整还原。

## 从源码构建

使用 Java 17 和 Gradle 8。项目的 `build.gradle` 使用本地 Minecraft 与 Forge 开发依赖。请将其中的 `libraries` 和 ForgeGradle 缓存路径对应到本机安装的位置，然后运行以下命令。输出的 Forge JAR 在 `build/libs/`。

```sh
gradle forgeJar
```

离线兼容性测试在 `src/test/`，部分测试需要自己提供对应版本的 BBS 或模型资源。
