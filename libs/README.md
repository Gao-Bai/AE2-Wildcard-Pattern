# libs/

编译期依赖放在这里，**不提交到仓库**（见 `.gitignore`）：

| 文件 | 说明 |
| --- | --- |
| `appliedenergistics2-forge-15.4.10.jar` | AE2 15.x 的 Forge 版，**必需**（编译与离线构建都要） |
| `jei-1.20.1-forge-15.20.0.129.jar` | JEI 15.x，**可选**（只用于「从 JEI 拖入」这段代码的编译） |

文件名里的版本号要和 `gradle.properties` 的 `ae2_version` / `jei_version` 对得上（或者直接改那里的版本号）。

用正常 Gradle 构建时不放这两个 jar 也能编译：`build.gradle` 在 `libs/` 找不到它们时会改用 ModMaven 坐标。
离线构建链（`tools/dev/build_offline.py`）则需要它们就位。
