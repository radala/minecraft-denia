# Fabric 版本与安装

| Minecraft Java 版 | 玩家使用的 JAR | 游戏 Java | 验证用 Fabric API |
|---|---|---|---|
| 1.21.11 | `lib/denia-0.1.0.jar` | 21 | `0.141.4+1.21.11` |
| 1.21、1.21.1 | `lib/Fabric-1.21-1.21.1/denia-0.1.0-fabric-1.21-1.21.1.jar` | 21 | 1.21 用 `0.102.0+1.21`，1.21.1 用 `0.116.14+1.21.1` |
| 1.20.1 | `lib/Fabric-1.20.1/denia-0.1.0-fabric-1.20.1.jar` | 17 | `0.92.6+1.20.1` |

在对应游戏版本的 Fabric 实例中，把一份达妮娅和对应版本的 Fabric API 放入 `mods/`。测试使用 Fabric Loader 0.19.3。联机时客户端和服务端都安装对应构件。每个版本单独建立游戏目录与存档。

形态、任务和背包沿用 `mcgf` 模组标识。新实例首次使用线上聊天或 Breeze 时，在模组设置里填入服务信息。按 V 听写使用客户端本机的 Whisper 服务。工作区里的 `Denia.cmd play-1.21.1` 和 `Denia.cmd play-1.20.1` 会随开发客户端启动该服务；双击 `Denia.cmd` 仍启动 1.21.11。源码运行的日常存档在各工程的 `run/saves`，测试另有独立目录。

## 开发与验证

在仓库根目录用 PowerShell 执行。构建工具使用 JDK 21，1.20.1 发布包以 Java 17 的类格式和 API 编译，1.20.1 生产验证使用 JDK 17。

```powershell
.\gradlew.bat -p ports/1.21.1 build
.\gradlew.bat -p ports/1.21.1/verification verifyReleaseServer verifyReleaseClient
.\gradlew.bat -p ports/1.21.1/verification -PtestGame=1.21 verifyReleaseServer verifyReleaseClient
.\gradlew.bat -p ports/1.21.1 stagePort

.\gradlew.bat -p ports/1.20.1 build
.\gradlew.bat -p ports/1.20.1/verification verifyReleaseServer verifyReleaseClient
.\gradlew.bat -p ports/1.20.1 stagePort
```

模型源文件仍是 `art/Denia-Blue.bbmodel`。所有版本从主工程读取模型、贴图、台词和语音素材。构建时优先选择目标版本的 Java 文件，1.20.1 还会复用 1.21.1 的实现。每个包只含一份运行时代码和资源。涉及已被某个版本替换的类时，改动后需联动检查对应实现。

验证工程以已打包的玩家 JAR 启动 Fabric 生产客户端和专服。报告在 `verification/build/<游戏版本>/gametest.xml`，实机截图在同目录的 `client/probe-evidence/`。测试模组单独构建。
