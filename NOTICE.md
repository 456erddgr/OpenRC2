# 声明 · 许可证 · 第三方

作者：**软糖布丁**。仓库地址见 GitHub 主页。

---

## 1. 本项目的许可证：GPLv2

本项目（`OpenRC2`，包名 `com.rc2.etx`）**链接并分发了 EdgeTX 的编译产物 `libetx.so`**，而 EdgeTX 采用 **GNU General Public License v2**。因此：

- 整个 App（含 Java 侧全部源码、JNI 桥、板级 HAL、构建脚本）**整体按 GPLv2 授权**；
- 你在分发本 App 的二进制（APK）时，**必须同时提供完整对应源码**，并保留本声明与版权信息；
- 你可以修改、再分发，但**衍生作品也必须 GPLv2**，且不得附加额外限制。

完整条文见仓库根目录 [`LICENSE`](LICENSE)（GPLv2 全文，339 行）。

```
OpenRC2 - EdgeTX native core ported to DJI RC2
Copyright (C) 2026 软糖布丁

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
```

---

## 2. 第三方组件

| 组件 | 版本 | 许可证 | 在本项目中的形态 |
|---|---|---|---|
| EdgeTX | 2.12.2（`3673c518`） | GPLv2 | 编成 `libetx.so`，板级 target 新增 `RC2V15`，vendor 改动 2 处（见下） |
| LVGL | 8.2（EdgeTX 内置） | MIT | 随 EdgeTX 一起静态链接进 so |
| Lua | 5.3（EdgeTX 内置） | MIT | 同上，用于 `SCRIPTS/` 里的 Lua 脚本 |
| ExpressLRS 脚本 | 随 V15 出厂卡 | GPLv3 | `sd/SCRIPTS/TOOLS/{elrsV3,elrsgyro,rf2,TBSAgent}.lua` 原样分发，未修改 |
| EdgeTX 主题/图标/字体 | 2.12.2 资产 | GPLv2 | `sd/THEMES`、`sd/IMAGES` 原样 |

**我们对 EdgeTX 上游的全部修改（vendor diff，共 2 处）**：

1. `radio/src/targets/horus/hal.h` —— 新增 `#if defined(RC2V15)` 分支：LCD 480×272、`ADC_DIRECTION {1,1,1,1,1,1,1}`。
2. `radio/src/gui/colorlcd/libui/table.cpp` —— `TableField::setRowCount()` / `select()` 首行加 `if (!lvobj) return;`，修 `menu.cpp` 先 `deleteLater()` 后 `onPress()` 造成的 use-after-free（SIGSEGV at `lv_table_set_row_cnt+132`）。

这两处改动同样以 GPLv2 提供，源码在 `RC2-EdgeTX-Native/vendor/`。

---

## 3. 重要声明（请务必读完）

### 3.1 与 DJI / 大疆无关

本项目是**第三方爱好者项目**，与 SZ DJI Technology Co., Ltd.（大疆创新）**没有任何隶属、授权、赞助或背书关系**。
`DJI`、`RC2`、`DJI RC 2` 等名称是其各自权利人的商标，本项目仅在"说明运行硬件"这一描述性意义上使用。

### 3.2 改机风险自负

- 本项目**要求设备已 root、已解锁 bootloader**。解锁/刷机通常会导致**保修失效**，并可能造成**设备变砖、数据丢失、射频校准丢失**。
- App 会通过 root 执行：`setprop sys.usb.config`、读写 `/config/usb_gadget`、`chmod /dev/hidg0`、读写 SD 卡配置目录。这些操作在错误时序下**可能让内核重启**（我们踩过，已加保护：只在没有节点时才重建 gadget）。
- **不要把本项目用于任何你在意保修的机器**。出了事，作者不承担任何责任。

### 3.3 无线电法规

- 机身自带射频**没有**被本项目用于发射 ELRS/CRSF。想做无线链路请用**合规的外置 ELRS 发射模块**（如 HappyModel / Radiomaster TX 模块），并遵守所在国家/地区的**发射功率、频段、执照**规定。
- 擅自改装发射功率或频段可能违法。

### 3.4 无担保

软件按"现状"提供，**不提供任何明示或暗示的担保**，包括但不限于适销性、特定用途适用性。因使用本软件导致的**炸机、财产损失、人身伤害**，作者不承担责任。

### 3.5 数据与隐私

- App **不联网**、不上报、无遥测、无广告、无统计 SDK。
- 唯一的外部通信是**本机回环** `127.0.0.1:40007`（读大疆摇杆数据）。
- 模型/配置只写在本机 `Android/data/com.rc2.etx/files/sd/`。

### 3.6 上游版权

EdgeTX 及其作者、贡献者的版权归其各自所有。本项目的存在完全建立在他们的工作上——如果你觉得这个 App 有用，请优先去 [edgetx.org](https://edgetx.org) 捐赠或贡献代码。

---

## 4. 源码获取

GPLv2 要求分发二进制时提供源码。对应关系：

| 二进制 | 对应源码 |
|---|---|
| `dist/RC2ETX-<ver>.apk` | 本仓库全部内容（Java 壳 + 构建脚本） |
| `lib/armeabi-v7a/libetx.so` | `RC2-EdgeTX-Native/`（含 `vendor/edgetx` 上游 + 2 处 diff + `scripts/build-*.sh`） |

构建方法见 README「构建」一节，可复现。
