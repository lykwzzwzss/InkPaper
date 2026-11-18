# 第三方开源组件、引用与许可声明 / Third-Party Notices

InkPaper（墨纸）以 **PolyForm Noncommercial License 1.0.0** 开源发布（完整条款见根目录 [`LICENSE`](LICENSE)）。本项目站在多位开源作者与社区研究者的工作之上，以下列出所有代码、算法、协议参考、预编译原生组件与依赖库的来源、用途及对应协议：

---

## 1. 核心协议与 Android NFC 架构上游

### `kur0neko/huawei-epd-pn532`
- **仓库**：<https://gitee.com/kur0neko/huawei-epd-pn532>（参考提交 `5d3acf730a988815b637262fd4862c1794ab079e`）
- **Required Notice**：`Required Notice: Copyright (c) 2026 kur0neko`
- **使用内容**：NFC APDU 通信时序（`D1` / `D8` / `PIN` / `D3` / `D4` / `DE`）、`552×768` 四色扫行像素打包格式及 LZO1X 分块流边界规范（参见 `PanelProtocol.kt`）。
- **协议**：PolyForm Noncommercial License 1.0.0（完整文本随源码及 APK 内置于 [`app/src/main/assets/licenses/kur0neko-LICENSE.txt`](app/src/main/assets/licenses/kur0neko-LICENSE.txt) 与 [`kur0neko-NOTICE.txt`](app/src/main/assets/licenses/kur0neko-NOTICE.txt)）。

### `yhl88890/qpoll-lite` & `yhl88890/huawei-nfc-eink-myron`
- **仓库**：
  - <https://github.com/yhl88890/qpoll-lite>
  - <https://github.com/yhl88890/huawei-nfc-eink-myron>
- **使用内容**：
  - 本项目是由 `qpoll-lite` 全面重构至 Kotlin + Jetpack Compose Material 3 的继任重写版本；
  - 继承并重构了双路设备供能策略（Android 15 SN100U `presence=90000` + `DE` 喂场保场 vs Android 16+/17 SN220U `D4` 后静默供能）、真 LZO1X 压缩编码器、`RootHook` / `ShizukuChannel` / `ShizukuBridgeService` 权限桥接与 `A1 / A0 / A0X` 屏型转换（参见 `NfcFlashSession.kt`、`PanelType.kt`、`PanelProtocol.kt`、`RootHook.kt`、`ShizukuChannel.kt`、`ShizukuBridgeService.kt`）；
  - 随包附带的 arm64 Root HAL 发现改写兼容组件 [`app/src/main/assets/native/arm64-v8a/libqpollhook.so`](app/src/main/assets/native/arm64-v8a/libqpollhook.so) 与 [`qpoll-inject`](app/src/main/assets/native/arm64-v8a/qpoll-inject) 源自 `yhl88890/qpoll-lite` / `yhl88890/huawei-nfc-eink-myron`（对应 C 源码位于上游 `huawei-nfc-eink-myron/native/`）。
- **协议**：PolyForm Noncommercial License 1.0.0。

### `ZhaoGY-N/huawei-nfc-eink-android-app`（QPollLab）及社区贡献者
- **仓库**：<https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app>
- **使用内容与贡献溯源**：
  - **ZhaoGY-N**：QPollLab Android 工程起点、`A0` 控制器奇偶行交错映射；
  - **CLF184**（[`fix/nfc-polling-a0x`](https://github.com/CLF184/qpoll-lite/tree/fix/nfc-polling-a0x)）：HyperOS Shizuku `POLLING_RESUME` 轮询恢复方案、`A0X` 验证；
  - **luzamm**（[PR #9](https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app/pull/9)）：`A0X` 左右半区交换原初实现；
  - **HBWuChang**（[Issue #3](https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app/issues/3) / <https://github.com/HBWuChang/huawei-nfc-eink-android-app>）：公开文档化的 SN220 HAL `0x71` 发现改写与租约门控方案；
  - **linckr**（[Issue #5](https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app/issues/5)）：4 参 `setReaderMode` 反射兼容与 `sendVendorNciMessage` API 边界结论。
- **协议**：PolyForm Noncommercial License 1.0.0（沿袭 QPollLab 及其分支谱系）。

### `wiwiwei/ink-nfc-lab`
- **仓库**：<https://github.com/wiwiwei/ink-nfc-lab>
- **使用内容**：四色抖动与伪彩色抑制算法参考（[`ReinkRaster.kt`](app/src/main/java/com/inkpaper/epd/protocol/ReinkRaster.kt) 中的 `smooth` 中性灰/冷蓝色门控 `suppressCoolChroma`、Atkinson / Sierra Lite / `BAYER_8X8` / 无抖动多风格对照架构）。
- **协议**：PolyForm Noncommercial License 1.0.0。

### `solar2ain/ink-nfc-fmsc-by-pn532`（协议研究参考）
- **引用提交**：`0f88cb315eab60e538c102e5883c5f957d9c1be0`（上游 GPL-3.0-or-later）
- **说明**：由 QPollLab 上游在逆向研究阶段作为 `D8` APDU、LZO 字面量结束标记及 `A0` 控制器行映射的公开协议对照资料。本仓库不包含、未拷贝也未链接其任何 Python 或原生代码模块。

---

## 2. 运行时依赖库、工具与运行时建议

### Shizuku API（`dev.rikka.shizuku:api:13.1.5` / `provider:13.1.5`）
- **仓库**：<https://github.com/RikkaApps/Shizuku-API>
- **Copyright**：Copyright (c) 2021 Rikka and contributors
- **协议**：**MIT License**（完整文本见 [`app/src/main/assets/licenses/Shizuku-LICENSE.txt`](app/src/main/assets/licenses/Shizuku-LICENSE.txt)）。
- **兼容说明**：Android 16+ / HyperOS 3.x 机型如使用 Shizuku 模式，建议配合 [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku/releases)（`13.7.0+`）运行，以规避官方 13.6 在 `LoadedApk.makeApplicationInner` 的空指针问题（见上游 [issue #7](https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app/issues/7)）。

### AndroidX / Jetpack Compose / Kotlin / Gradle
以下组件通过标准 Gradle Maven 依赖或构建包装引入，均为 **Apache License 2.0**：
- `androidx.core:core-ktx`
- `androidx.activity:activity-compose`
- `androidx.lifecycle:lifecycle-runtime-ktx` / `lifecycle-runtime-compose` / `lifecycle-viewmodel-compose`
- Jetpack Compose BOM（`ui`, `ui-graphics`, `ui-tooling-preview`, `material3`, `foundation`）
- Kotlin Android / Kotlin Compose Compiler Plugin（`2.0.21`）
- Gradle Wrapper（`gradle/wrapper/`）

### JUnit 4（仅单元测试）
- `junit:junit:4.13.2` — **Eclipse Public License 1.0 (EPL-1.0)**，仅用于 `app/src/test/` 单元测试，不打包进最终 APK。
