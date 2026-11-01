#!/usr/bin/env bash
# 构建 RC2 EdgeTX APK：aapt2/javac/d8 原生链 → 注入 jniLibs 下的 .so → zipalign → apksigner。
# 用法: ./scripts/build-apk.sh 0.6.0     （产物 dist/RC2ETX-<ver>.apk）
#
# 重要：本 ROM 的 PackageManager 对"新装 App"会强制 abiOverride=armeabi-v7a
# （logcat: [DJIDEBUG] installPackagesLI add abiOverride:armeabi-v7a），
# 只有 arm64-v8a 的 APK 会 INSTALL_FAILED_NO_MATCHING_ABIS(-113)，并被包装成
# "Error deriving application ABI"。所以 **必须** 提供 armeabi-v7a 的 .so。
set -e
cd "E:/我的项目/RC2-EdgeTX"
VER="${1:-dev}"
PROJ='E:\我的项目\RC2-EdgeTX'
SDK='E:/我的项目/rc331work/sdk'
JAVA="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot/bin/java.exe"
PY="/c/Users/刘子墨/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/python.exe"
mkdir -p dist build

# ---- 冻结 so 来源：默认注入 keep/libetx.so 基线；要试新 so 用 ETX_SO=/path/xx.so ----
SO_V7=app/jniLibs/armeabi-v7a/libetx.so
if [ -n "$ETX_SO" ]; then
  cp "$ETX_SO" "$SO_V7"; echo "[i] 使用 ETX_SO=$ETX_SO"
else
  cp keep/libetx.so "$SO_V7"; echo "[i] 使用基线 keep/libetx.so"
fi

# ---- GUARD: so 未定义符号检查 ----
# 只拦"基线没有、且 libc/libm/libdl/liblog 也提供不了"的符号（真正的坏构建），
# 放行 Lua 等新功能引入的 libc/libm 依赖。
SO=app/jniLibs/armeabi-v7a/libetx.so
BASE=keep/libetx.so
NDKB=/e/我的项目/Android/Sdk/ndk/26.3.11579264/toolchains/llvm/prebuilt/windows-x86_64
NM=$NDKB/bin/llvm-nm.exe
SYSLIB=$NDKB/sysroot/usr/lib/arm-linux-androideabi/29
if [ -f "$SO" ] && [ -f "$BASE" ]; then
  mkdir -p build
  "$NM" -D --undefined-only "$SO"   2>/dev/null | awk '{print $2}' | sed 's/@.*//' | sort -u > build/_so_new.txt
  "$NM" -D --undefined-only "$BASE" 2>/dev/null | awk '{print $2}' | sed 's/@.*//' | sort -u > build/_so_base.txt
  : > build/_so_allow.txt
  for L in libc.so libm.so libdl.so liblog.so; do
    [ -f "$SYSLIB/$L" ] && "$NM" -D --defined-only "$SYSLIB/$L" 2>/dev/null | awk '{print $NF}' | sed 's/@.*//' | sort -u >> build/_so_allow.txt
  done
  sort -u build/_so_allow.txt -o build/_so_allow.txt
  comm -23 build/_so_new.txt build/_so_base.txt > build/_so_newonly.txt
  BAD=$(comm -23 build/_so_newonly.txt build/_so_allow.txt)
  if [ -n "$BAD" ]; then
    echo "[FATAL] libetx.so 引入了基线没有、libc/libm 也提供不了的未定义符号（多半是构建产物陈旧）："
    echo "$BAD"
    echo "        换回 keep/libetx.so 或让上游重走完整构建链"
    exit 1
  fi
  printf "[ok] so 符号检查通过  %s  新增libc依赖=%s\n" "$(sha256sum "$SO" | cut -c1-16)" "$(wc -l < build/_so_newonly.txt | tr -d ' ')"
fi
HAVE_V7=0; HAVE_64=0
[ -f app/jniLibs/armeabi-v7a/libetx.so ] && HAVE_V7=1
[ -f app/jniLibs/arm64-v8a/libetx.so ] && HAVE_64=1

TARGET="$PROJ\dist\RC2ETX-$VER.apk"
if [ $HAVE_V7 = 1 ] || [ $HAVE_64 = 1 ]; then
  TARGET="$PROJ\dist\_base-$VER.apk"
fi

pwsh -NoProfile -ExecutionPolicy Bypass -File "$SDK\build-apk.ps1" -Root "$PROJ\app" -Out "$TARGET" -MinApi 29 | tail -6

if [ $HAVE_V7 = 1 ] || [ $HAVE_64 = 1 ]; then
  echo "[+] 注入 .so (v7=$HAVE_V7 v8a=$HAVE_64)"
  rm -rf build/inj && mkdir -p build/inj
  cp "dist/_base-$VER.apk" build/inj/base.apk
  "$PY" scripts/inject_so.py build/inj/base.apk app/jniLibs build/inj/raw.apk app/assets
  "$SDK\zipalign.exe" -f -p 4 build/inj/raw.apk build/inj/aligned.apk
  "$JAVA" -cp "$SDK\lib\apksigner.jar" com.android.apksigner.ApkSignerTool sign \
    --ks "$SDK\debug.keystore" --ks-pass pass:rc2debug123 --key-pass pass:rc2debug123 \
    --min-sdk-version 29 --out "E:/我的项目/RC2-EdgeTX/dist/RC2ETX-$VER.apk" build/inj/aligned.apk
  rm -f "dist/_base-$VER.apk"
fi

unzip -l "dist/RC2ETX-$VER.apk" | grep -E "lib/|classes.dex" || true
echo "[ok] dist/RC2ETX-$VER.apk"
