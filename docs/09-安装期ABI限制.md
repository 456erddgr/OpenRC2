# 09 · 安装期 ABI 限制（2026-11-01 实测，重要）

## 结论
这台 DJI RC2 的 PackageManager 被定制过：**任何新装第三方 App 都会被强制 `abiOverride=armeabi-v7a`**。
只有 `lib/arm64-v8a/` 的 APK 装不上，会返回一句极具误导性的 `INSTALL_FAILED_INTERNAL_ERROR: Error deriving application ABI`。

## 证据链
1. `adb install` 失败信息（只有表象）：
   ```
   Failure [INSTALL_FAILED_INTERNAL_ERROR: Error deriving application ABI]
   ```
2. `services.jar` 反汇编定位（`dexdump -d` + 行号）：
   - `PackageManagerService.preparePackageLI` 里 `const-string "Error deriving application ABI"` 后接
     `Slog.e("PackageManager", ..., throwable)` + `new PrepareFailure(v4, msg)` —— **它只是一个 catch-all 包装**，
     真实异常被吞掉。
3. 抓真实异常（`adb logcat -s PackageManager:V`，安装后立刻取）：
   ```
   W PackageManager: [DJIDEBUG] installPackagesLI add abiOverride:armeabi-v7a for /data/app/vmdl*.tmp
   E PackageManager: com.android.server.pm.PackageManagerException: Error unpackaging native libs for app, errorCode=-113
   E PackageManager:  at com.android.server.pm.PackageAbiHelperImpl.derivePackageAbi(PackageAbiHelperImpl.java:429)
   ```
   `-113` = `INSTALL_FAILED_NO_MATCHING_ABIS`。

## 为什么别人能装
设备上已装的第三方 App 都带 32 位 so：`me.hisn.mygesture`（arm+arm64）、`com.kugou.android.auto`（arm+arm64）、
`bin.mt.plus`（arm+arm64）、`com.ss.android.ugc.awemenplay`（arm）。`dji.go.v5` 只有 arm64，但它是系统预装（不走这条新装路径）。

## 对我们工程的硬约束
| 项 | 要求 |
| --- | --- |
| so ABI | **必须有 `lib/armeabi-v7a/libetx.so`**；arm64 可选一起带 |
| 工具链 | `armv7a-linux-androideabi29-clang++`（NDK r26d） |
| 链接 | `-shared -fPIC -static-libstdc++ -llog -Wl,-soname,libetx.so` |
| 运行时库 | 不要单独打包 `libc++_shared.so`，一律静态链接 |
| 其他 | `extractNativeLibs="true"`，`minSdk=29 targetSdk=29` |

## 验证（桩 so，2026-11-01 00:05）
```
available=true
sd=/storage/emulated/0/Android/data/com.rc2.etx/files/sd
radio_yml=true
init_rc=0
version=stub-0.1 (yml ok)
screen=480x272
render=1  argb_sampled_nonzero=1346/1346
channels=16 us=[992 x16]
```
桩画面：`out/native_frame.png`（480×272 渐变 + 左侧白色竖条 + 顶部 4 个青绿方块对应 4 轴位置）。

## 复现命令
```bash
NDK=/e/我的项目/Android/Sdk/ndk/26.3.11579264/toolchains/llvm/prebuilt/windows-x86_64/bin
$NDK/armv7a-linux-androideabi29-clang++.cmd -shared -fPIC -O2 -fvisibility=default \
  -static-libstdc++ -llog -Wl,-soname,libetx.so \
  -o app/jniLibs/armeabi-v7a/libetx.so app/jniStub/etx_stub.cpp app/jniStub/etx_jni.cpp
./scripts/build-apk.sh 0.6.3          # 自动注入 + zipalign + apksigner
adb install -r dist/RC2ETX-0.6.3.apk
adb shell am start -n com.rc2.etx/.MainActivity --es action native
adb shell cat /sdcard/Android/data/com.rc2.etx/files/native.txt
```
