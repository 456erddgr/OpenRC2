# 板级 HAL 实现口径（2026-11-01 定稿）

> 原生核心 `libetx.so` 现在还差 141 个板级符号（清单在 `RC2-EdgeTX-Native/docs/07-下半身HAL清单.md`）。
> 这份文档写死"每一组该怎么实现"，避免实现者按自己理解填。

## 1. 输入轴（最重要，先看这张表）

`etx_set_inputs(axes, nAxes, sw, nSw, knobs, nKnobs)`：

| 参数 | 7 轴模式（目标） | 4 轴模式（兼容旧包） |
|---|---|---|
| `axes[0..3]` | `RH, RV, LV, LH` | 同左 |
| `axes[4..6]` | `P1, P2, P3` | 无（取中位 1024） |
| `sw[i]` | `boardSwitchGetValue(i)`，`i>=nSw` 返回 0 | 同左 |
| `knobs` | 7 轴时忽略（传 `nKnobs=0`） | 2 个旋钮 |
| 量程 | 0..2047，中位 1024 | 同左 |

**为什么是 RH 在前**：K5 交叉校验实测（`docs/13`），2.12.2 核心按 MCU 物理序吃输入。
写成 `LH,LV,RV,RH` 会让副翼/俯仰对调。

## 2. DMA / 显示（11 个符号）

- **全部纯软件实现**，不要找真 DMA 通道。
- `DMACopyBitmap`：逐行 memcpy，**同时**按 `dst` 与 `src` 的 stride 计算（stride = width*2 字节，16bpp）。
- `DMACopyAlphaBitmap` / `DMACopyAlphaMask`：整数 565 混合，禁止循环内浮点。
- `lcdInit` / `lcdSetInitalFrameBuffer`：只记指针，不刷屏（刷屏由 `etx_render` 负责）。
- `startSplash` / `cancelSplash`：空实现。
- `currentBacklightBright`：返回 100。

## 3. 开关 / 电位器 / 按键

- `boardSwitchGetValue(i)` → `sw[i]`；`isSwitchAvailable(i)` → `i<4`。
- `getPotType` 返回固定确定值，`setPotType` 空实现（**不要随机**）。
- `keysGetMaxKeys` 等常量返回 EdgeTX 枚举上限，**不许返回 0**（会越界）。

## 4. 遥测 / 模块

- 没有硬件要"明确表达缺失"：返回 `nullptr` / `false`，**不要返回 0 冒充有值**。
- `isModuleAvailable(INTERNAL_MODULE)` = false，`EXTERNAL_MODULE` = true（ELRS 走 CRSF 外置口）。

## 5. 声音 / 存储 / 时间

- 声音：全空实现，`audioInit` 必须可安全调用（不崩不阻塞）。
- 存储：根目录 = `<files>/sd`；`RADIO/radio.yml`、`MODELS/model1.yml` 只读必须成功；写失败返回错误码不得崩。
- 时间：`clock_gettime(CLOCK_MONOTONIC)`，**禁用 `time()`**（墙钟跳变会害死定时器）。

## 6. 我们自己新增的文件（非 vendor 改动）

- `out/halstub/lua_shim.h`：`luaInitMainState()` 空实现（Lua submodule 不在 tarball）。
- `out/generated/yaml_inputs.inc`：手写重建（源码包无 hw JSON）。
- `scripts/gen-yaml.sh` + `out/generated/yaml_datastructs_rc2v15.cpp`：YAML 生成物（992 行，22 张 `YamlIdStr` 表）。

这些都要出现在 GPLv2 交付清单里（`THIRD-PARTY-NOTICES.md` §三）。
