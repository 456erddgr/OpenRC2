# 第三方组件与许可（GPLv2 合规清单）

本项目把 EdgeTX 的真实 C++ 源码编进 Android 应用，**必须**按 GPLv2 开源整个项目。
本文件是合规清单，随每个发布版本更新。

## 一、随 APK 分发的第三方代码

| 组件 | 版本/commit | 许可 | 在 APK 里的位置 | 是否修改 |
|---|---|---|---|---|
| EdgeTX | v2.12.2（HEAD `3673c51811b313fd634be4faa12128803956c53f`） | **GPLv2** | `lib/armeabi-v7a/libetx.so`（静态链接核心 + 混控 + yaml 解析） | 见 `docs/01-改动登记表.md` |
| LVGL | `5f129c540ec43a4e5aebff9f77b3688b57a78063`（EdgeTX/lvgl, release/v8.2） | MIT | 同上（静态链接） | 否 |
| EdgeTX 内置字体/位图 | 随 v2.12.2 | GPLv2 | 同上 | 否 |
| stb | EdgeTX 指定 commit | MIT / Public Domain | 同上（headers-only） | 否 |

**结论**：`libetx.so` = EdgeTX(GPLv2) + LVGL(MIT) 静态链接的产物，整个 APK 按 GPLv2 发布。
LVGL 是 MIT，与 GPLv2 兼容，不需要额外动作，但保留版权声明。

## 二、本仓库自有代码

| 部分 | 许可 |
|---|---|
| `app/src/**`（Java 外壳：UI、混控镜像、CRSF 编码、USB CDC、DUML 摇杆客户端） | GPLv2（与主项目一致） |
| `lab/firmware/**`（ESP32-S3 CRSF 桥，Arduino） | GPLv2 |
| `scripts/**` | GPLv2 |

## 三、分发时必须一并提供

1. **完整源码**：本仓库 `git archive` 出来的 tarball（含 `app/`、`docs/`、`scripts/`、
   `vendor/edgetx-src-2.12.2` 的**改动补丁**或完整树、`app/jniLibs` 的**源码**）。
2. **构建脚本**：能复现 `libetx.so` 的命令行（见 `docs/12-移植路线与验收标准.md` §构建）。
3. `docs/01-改动登记表.md`：对 vendor 的每一处修改（文件:行 + 原因 + 原文对照）。
4. 本文件 + `LICENSE`（GPLv2 全文）。

## 四、不该做的事（自查）

- ❌ 不发布只给 `.so` 不给源码的 APK。
- ❌ 不在 `libetx.so` 里链进闭源库（当前没有任何闭源依赖）。
- ❌ 不把 EdgeTX 的 GPLv2 代码与 DJI 的私有代码混编（我们没有反编译/链接任何 DJI 二进制）。
