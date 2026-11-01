#!/usr/bin/env bash
# 把 V15 工厂 SD 的配置目录推到 RC2 的 App 私有目录，供 libetx.so 的 etx_init(sdPath) 读取。
# 用法: ./push-sd.sh            （源: E:\我的项目\V15-factory，目标: /sdcard/Android/data/com.rc2.etx/files/sd）
set -e
export MSYS2_ARG_CONV_EXCL='*'
ADB="/e/我的项目/rc2-backup/tools/platform-tools/adb.exe -s 6UZBM6L02104CA"
SRC="/e/我的项目/V15-factory"
PKG=com.rc2.etx
DST="/sdcard/Android/data/$PKG/files/sd"
DIRS="RADIO MODELS THEMES WIDGETS SCRIPTS SOUNDS IMAGES TEMPLATES"
TAR=/data/local/tmp/v15sd.tar

echo "[1/4] 打包配置目录（不含 FIRMWARE/Elrs/Ai 固件二进制）"
mkdir -p "/e/我的项目/RC2-EdgeTX/build"
LOCAL="/e/我的项目/RC2-EdgeTX/build/v15sd.tar"
rm -f "$LOCAL"
cd "$SRC"
tar cf "$LOCAL" $DIRS 2>/dev/null || true
ls -l "$LOCAL"

echo "[2/4] 推送"
cd "/e/我的项目/RC2-EdgeTX"
$ADB push build/v15sd.tar $TAR 2>&1 | tail -1

echo "[3/4] 设备端解开（root，然后交还给 App 的 uid）"
$ADB shell "/debug_ramdisk/su -c 'mkdir -p $DST && cd $DST && tar xf $TAR && UID=\$(stat -c %u /data/data/$PKG) && chown -R \$UID:\$UID $DST && ls $DST'"

echo "[4/4] 关键文件校验"
$ADB shell "ls -l $DST/RADIO/radio.yml $DST/MODELS/model1.yml 2>&1 | head -4"
echo "[ok] SD 已就位: $DST"
