# InkPaper（墨纸）

面向 **3.98 寸四色无源 NFC 电子纸手机壳（JD79665AA · `552×768` · 黑/白/黄/红）** 的 Android 直刷应用，基于 Kotlin 与 Jetpack Compose（Material 3）从 [QPoll Lite](https://github.com/yhl88890/qpoll-lite) 完整重写与精简。

> [!IMPORTANT]
> 本项目遵循 **PolyForm Noncommercial License 1.0.0**（非商业使用许可）。完整授权与上游致谢见 [`LICENSE`](LICENSE) 与 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

---

## 主要特性

- **极简单屏 Material 3 界面**：主屏直接呈现 1:1 像素对齐（`552×768`）成图预览，点击预览可打开双指捏合/双击放大的像素级检查器（`PixelZoomDialog`）；底部仅保留 `A1 / A0 / A0X` 屏幕物理行序切换与刷屏触发控制。
- **五种四色抖动与出图风格**（[`ReinkRaster.kt`](app/src/main/java/com/inkpaper/epd/protocol/ReinkRaster.kt)）：
  - **质感**：柔和 S 曲线 `SOFT_TEXTURE_LUT` + `Redmean` 感知距离 + 冷灰抑制 + 蛇形 Atkinson 扩散（肤色、褐色衣物、白发通透）。
  - **鎏金**：原色还原 + `SIERRA_LITE` 蛇形扩散，适合柔和渐变照片。
  - **网点**：`Bayer 8×8` 有序抖动结合 `Redmean` 与冷灰黑白门控，无拖尾误差传播，适合漫画与印刷网格风格。
  - **色阶**：`LEVEL_POSTER_LUT` 高反差阶调 + 暖色提纯无抖动，适合海报、文字与赛璐璐色块。
  - **水墨**：BT.709 灰阶 + 2%–98% 自动对比拉伸 + 焦墨/宣纸反差曲线 + 黑白 Atkinson 抖动。
  - **内置冷灰/冷蓝抑制**：在所有彩色模式下将低饱和灰色与冷蓝青色平滑收敛到纯黑白通道，防止蓝瞳、白发与灰阴影出现红黄杂点。
- **全自动最高权限选择**（[`PrivilegeManager.kt`](app/src/main/java/com/inkpaper/epd/nfc/PrivilegeManager.kt)）：自动按 **Root HAL 兼容层 > Shizuku Shell > 系统直连** 判定并启用最高可用通道，无需手动切换调试开关。
- **默认官方查电（`D4` 字节 4 = `0x00`）+ 双路供能策略**（[`NfcFlashSession.kt`](app/src/main/java/com/inkpaper/epd/nfc/NfcFlashSession.kt)）：
  - Android 15 / NXP SN100U 路线：长 presence 延迟 + `DE` 喂场；
  - Android 16+ / HyperOS 3.x / NXP SN220U 路线：兼容 KernelSU/Root HAL 发现改写（`0x71`）及 Shizuku 轮询恢复，保持稳定无源耦合。
- **真 LZO1X 分块压缩与实时非白占比统计**：按 2000 字节块自适应压缩，降低 NFC 传输耗时。
- **浅色纸纹自适应图标**：配套 SVG 与 Android 26+ Adaptive Icon（[`design/app_icon_a.svg`](design/app_icon_a.svg)）。

---

## 支持硬件与权限路径

| 屏幕规格 | 行序选项 | 说明 |
|---|---|---|
| 3.98" JD79665AA 四色屏（`552×768`） | `A1`（默认）/ `A0` / `A0X` | `D1` 指令无法区分排线物理行序，需按实物选择（常规直排选 `A1`；奇偶行交错选 `A0`；左右半区互换交错选 `A0X`） |

- **已实机验证**：Redmi K90（Android 17，KernelSU Root 路径，NXP NCI HAL 兼容层部署后零弹窗直刷，多次连续 `D4 → SW=9000`）。
- **Shizuku 路径提示**：若设备未 Root、在 Android 16+ / HyperOS 3.x 上使用 Shizuku，请安装 [**thedjchi/Shizuku 13.7.0+**](https://github.com/thedjchi/Shizuku/releases)（修复了原版 13.6 在新系统 `LoadedApk.makeApplicationInner` 绑定 UserService 时的 NPE）。

---

## 从源码构建与测试

### 环境要求
- JDK 17（请设置环境变量 `JAVA_HOME`）
- Android SDK（`compileSdk = 36`，`targetSdk = 35`，`minSdk = 26`，设置 `ANDROID_HOME` 或在本地配置 `local.properties`）

### 构建 Release APK

```bash
./gradlew assembleRelease
```

产物输出在 `app/build/outputs/apk/release/`。

### 运行单元测试

包含面板行序打包/预览逆变换（`A1/A0/A0X`）、`D4` 指令编码、LZO1X 压缩边界、五种出图风格与冷灰/蓝天纯黑白断言：

```bash
./gradlew testDebugUnitTest
```

---

## 上游项目与开源协议（致谢清单）

本项目基于以下开源项目与社区成果构建，全部遵守对应许可协议：

| 项目 / 作者 | 贡献与在本仓库中的用途 | 开源协议 |
|---|---|---|
| [`kur0neko/huawei-epd-pn532`](https://gitee.com/kur0neko/huawei-epd-pn532) | 最初的 NFC APDU 协议（`D1/D8/PIN/D3/D4/DE`）、扫行 2bpp 打包与 LZO1X 帧规范 | **PolyForm Noncommercial 1.0.0**（含 `Required Notice: Copyright (c) 2026 kur0neko`） |
| [`yhl88890/qpoll-lite`](https://github.com/yhl88890/qpoll-lite) & [`yhl88890/huawei-nfc-eink-myron`](https://github.com/yhl88890/huawei-nfc-eink-myron) | 本项目重构前的直接上游：双路芯片/系统供能画像、LZO1X 压缩器、Shizuku UserService 桥接与 `arm64-v8a` Root HAL 兼容组件（`libqpollhook.so` / `qpoll-inject`） | **PolyForm Noncommercial 1.0.0** |
| [`ZhaoGY-N/huawei-nfc-eink-android-app`](https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app) 及贡献者 `CLF184` / `luzamm` / `HBWuChang` / `linckr` | QPollLab 工程基底、`A0` 行交错映射、`A0X` 半屏交换（[PR #9](https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app/pull/9)）、`POLLING_RESUME`、SN220 HAL `0x71` 改写思路（[Issue #3](https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app/issues/3)）与兼容性实测（[Issue #5](https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app/issues/5)） | **PolyForm Noncommercial 1.0.0** |
| [`wiwiwei/ink-nfc-lab`](https://github.com/wiwiwei/ink-nfc-lab) | 五档出图中的冷色/中性灰门控与 `Atkinson / Sierra Lite / Bayer 8×8 / 无抖动` 多模式算法框架参考 | **PolyForm Noncommercial 1.0.0** |
| [`solar2ain/ink-nfc-fmsc-by-pn532`](https://github.com/solar2ain/ink-nfc-fmsc-by-pn532) | 协议文档研究阶段参考（`D8`、`A0` 映射记录）；本仓库未包含或链接其源码/二进制 | **GPL-3.0-or-later**（仅参考） |
| [`RikkaApps/Shizuku-API`](https://github.com/RikkaApps/Shizuku-API) (`13.1.5`) | 免 Root Shell 权限 UserService 通道 | **MIT License** |
| AndroidX / Jetpack Compose / Material 3 / Kotlin (`2.0.21`) / Gradle Wrapper | 应用框架、UI 组件库与构建工具链 | **Apache License 2.0** |
| JUnit 4 (`4.13.2`) | 离线单元测试框架（不进 APK） | **EPL-1.0** |

完整第三方版权与声明文本请见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) 与 [`NOTICE`](NOTICE)。

---

## 开源许可

本项目以 **PolyForm Noncommercial License 1.0.0** 发布，仅限非商业用途、个人学习、研究与爱好者 DIY 使用。不得用于商业用途，亦不得去掉上游 `Required Notice` 或擅自变更兼容的开源协议声明。
